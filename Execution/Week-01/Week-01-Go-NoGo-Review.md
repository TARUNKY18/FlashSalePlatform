# Final Review — docker-compose.yml (Post-Patch)
**Reviewer:** Senior Staff Platform Engineer
**File:** `deployment/docker/docker-compose.yml`
**Review date:** 2026-06-16
**Prior patch:** `docker-compose-M1-M3.patch` (fixes for M1 + M3)

---

## Verdict

**NO-GO.**

One critical new bug was introduced by the M1 fix. The idempotency guard uses
incorrect shell variable escaping inside a Docker Compose YAML block scalar.
The guard appears syntactically valid but does not function at runtime.
The original M1 failure mode is still present.
A second fix pass is required before `make up`.

---

## Review by Area

### 1. Postgres Startup — CONDITIONAL PASS

**M3 fix verified — PASS.** Line 122 is now correctly quoted:
```yaml
- "log_line_prefix=%t [%p] [%a] user=%u,db=%d"
```
YAML double-quotes preserve the full string as one argv element through the Postgres
entrypoint. `sales-db` will start. M3 is confirmed resolved.

**PGDATA subdirectory pattern — PASS.** All three instances set:
```yaml
PGDATA: /var/lib/postgresql/data/pgdata    # data directory
# volume mounts at:
- xxx-db-data:/var/lib/postgresql/data     # parent
```
Using a subdirectory inside the volume root is the correct pattern for the official
Postgres image. It separates cluster data from other files the image may write to
the volume root and prevents init script conflicts.

**healthcheck `$$POSTGRES_DB` — PASS.** In exec-form healthchecks, `$$VAR` causes
Docker Compose to substitute at container creation time, placing a literal `$VAR`
in the healthcheck command, which the container's shell then evaluates against the
container's own environment. All three instances set `POSTGRES_DB` explicitly. This
is correct.

**Asymmetry: `log_line_prefix` on `sales-db` only — INFORMATIONAL.**
`inventory-db` and `orders-db` have no `log_line_prefix` in their command blocks.
Both will start and function correctly; their log lines will simply be unformatted.
Deferred to Week 2 cleanup (M23). Not a blocker.

---

### 2. Redis Cluster Startup — FAIL

**The M1 fix introduced a new escaping bug. M1 is not resolved.**

#### Root cause: `\$` is not the correct escape in Docker Compose block scalars

The idempotency guard must do two things:
1. Run `redis-cli` and capture its output into `CLUSTER_STATE`
2. Compare `CLUSTER_STATE` to the string `ok`

Both operations depend on correct shell variable expansion. In a Docker Compose
YAML block scalar (`>`), there are three processing layers between what is written
and what the shell executes:

**Layer 1 — YAML block scalar:** Does not process escape sequences. All characters
pass through verbatim to Docker Compose.

**Layer 2 — Docker Compose variable substitution:** Compose applies one escape rule
relevant here: `$$` → `$` (escaped dollar). The sequence `\$` is not a compose
escape — compose passes the backslash and the dollar through unchanged.

**Layer 3 — Shell:** Receives the string after compose substitution and executes it
as sh script. In POSIX sh, `\$` (backslash + dollar) is an escaped dollar — it
produces a literal `$` character, suppressing variable and command substitution.

**Tracing the two broken lines (od -c verified the exact bytes):**

Line 331 — variable assignment:
```
File bytes: CLUSTER_STATE=\$(redis-cli \
```
- YAML: passes through unchanged
- Compose: `\$` — not a compose pattern, passes through as `\$`
- Shell receives: `CLUSTER_STATE=\$(redis-cli ...)`
- Shell: `\$` = escaped dollar = literal `$`, not command substitution
- **Result:** `CLUSTER_STATE` is assigned the literal string `$(redis-cli ...)`,
  never the output of the command

Line 337 — the if-condition:
```
File bytes: if [ \"\\$$CLUSTER_STATE\" = 'ok' ]; then
```
- YAML: passes through unchanged
- Compose: `$$` → `$`, so `\\$$CLUSTER_STATE` → `\\$CLUSTER_STATE`
- Shell receives: `if [ \"\\$CLUSTER_STATE\" = 'ok' ]`
- Shell: `\"` = literal quote, `\$CLUSTER_STATE` = literal string `$CLUSTER_STATE`
- **Result:** comparison is `[ "$CLUSTER_STATE" = 'ok' ]` comparing a literal
  string to `ok` — always false

**Combined effect:** `CLUSTER_STATE` is never populated. The condition is never
true. The guard never skips cluster creation. On any `make up` after the first,
`--cluster create` runs on an existing cluster and exits non-zero. M1 is still
broken.

#### Correct escaping rule

In Docker Compose YAML block scalars, to produce a `$` that the **shell** processes
as variable or command substitution, write `$$` in the compose file.

Compose converts `$$` → `$`. The shell sees a normal `$` and expands it.

`\$` is wrong because the shell sees `\$` and treats the dollar as escaped/literal.

**Corrected lines:**

```yaml
# Assignment — was \$(  now $$(
CLUSTER_STATE=$$(redis-cli \
  -a ${REDIS_PASSWORD:-redis_dev} \
  --no-auth-warning \
  -h redis-node-1 -p 6379 \
  CLUSTER INFO 2>/dev/null | grep cluster_state | cut -d: -f2 | tr -d '[:space:]')

# Comparison — was \"\\$$  now \"$$
if [ "$$CLUSTER_STATE" = 'ok' ]; then
```

Note: `${REDIS_PASSWORD:-redis_dev}` is intentionally left as single-dollar.
These are Docker Compose substitutions — Compose should expand them before
the shell sees the script, which is the desired behaviour here.

**Compose → shell translation after fix:**

```
Compose file:  $$(redis-cli ...)
Compose sends: $(redis-cli ...)       <- $$ becomes $
Shell sees:    command substitution   <- correct

Compose file:  "$$CLUSTER_STATE"
Compose sends: "$CLUSTER_STATE"       <- $$ becomes $
Shell sees:    variable reference     <- correct
```

#### Other Redis checks — PASS

- `cluster-config-file /data/nodes.conf` resolved against `/data` volume mount: correct
- `cluster-announce-hostname redis-node-N` uses Docker service names: correct,
  stable across restarts, no IP drift
- All 6 nodes use the same `redis-node.conf` mounted read-only at the same path
  as the command loads it from: consistent
- `depends_on: condition: service_healthy` on all 6 nodes before cluster-init runs:
  correct ordering

---

### 3. Kafka Startup — PASS (with informational notes)

**KRaft cluster ID — PASS.** `KAFKA_KRAFT_CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qg==`
decodes to a valid 16-byte value. Bitnami Kafka 3.7 accepts base64 with padding.
The ID is hardcoded in the compose file and backed by a named volume — stable across
restarts as long as the volume persists.

**Dual-listener setup — PASS.** `INTERNAL://kafka:29092` and `EXTERNAL://localhost:9092`
are correctly configured. Port 29092 is intentionally absent from the `ports:` section
(it is Docker-network internal only). Port 9092 is mapped to the host. Kafka UI, running
inside Docker, uses `kafka:29092`. Host tooling uses `localhost:9092`. This is correct.

**`KAFKA_CFG_ENABLE_IDEMPOTENCE: "true"` — INFORMATIONAL.** This is a producer-side
setting, not a broker setting. Bitnami Kafka will log a warning and ignore it. No
operational impact. Known deferred issue M4.

---

### 4. Kafka UI Startup — PASS (with one misconfiguration)

**Bootstrap server — PASS.** `KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:29092` is correct.
Kafka UI is a container on `flash-sale-net`; the INTERNAL listener address resolves within
the network. `depends_on: kafka: condition: service_healthy` ensures the broker is ready.

**`KAFKA_CLUSTERS_0_METRICS_PORT: 9092` — MISCONFIGURATION, NON-BLOCKING.** Port 9092 is
the broker's plaintext listener, not a JMX port. Kafka UI expects JMX (typically 9997) for
broker metrics. The metrics panel in the UI will fail or be empty. Topic browsing, consumer
group lag view, and message inspection all function correctly — they use the bootstrap server,
not the metrics port. Known deferred issue M5.

---

### 5. Docker Networking — PASS

**All services on `flash-sale-net` — PASS.** Every service is on the shared network:
- `sales-db`, `inventory-db`, `orders-db` — via `*postgres-common` anchor
- `redis-node-1` through `redis-node-6` — via `*redis-node-common` anchor
- `redis-cluster-init`, `kafka`, `clickhouse`, `kafka-ui`, `redisinsight` — explicit declaration

All 14 services can reach each other by container/service name within the network.

**Subnet `172.28.0.0/16` — PASS.** Within RFC 1918 `172.16.0.0/12`. Does not conflict
with Docker's default bridge (`172.17.0.0/16`) or common VPN ranges.

**`redis-cluster-init` network membership — PASS.** Explicitly on `flash-sale-net`.
Required for `redis-node-1:6379` through `redis-node-6:6379` hostname resolution
during cluster creation. Without this, `redis-cli --cluster create` would fail
immediately with connection errors.

---

### 6. Volume Persistence — PASS

**All 12 declared volumes are mounted — PASS.** Every entry in the top-level `volumes:`
block is referenced in a service `volumes:` mount. No orphaned declarations.

**`PGDATA` as subdirectory — PASS.** Setting `PGDATA` to a subdirectory of the volume
mount (`/data/pgdata` inside `/data`) is the correct pattern. It allows the volume root
to hold other files the Postgres image may write without conflicting with the cluster
data directory.

**`clickhouse-logs` volume — PASS.** A dedicated volume for ClickHouse logs prevents
log growth from consuming the data volume.

**Redis `nodes.conf` persistence — PASS.** `cluster-config-file /data/nodes.conf`
combined with each node's dedicated volume means cluster topology survives
`docker compose down && up` (volumes not deleted). This is correct behaviour — the
cluster re-forms itself from persisted state without needing to re-run the init container.

**Kafka data volume — PASS.** `/bitnami/kafka` is the correct path for the Bitnami
image. KRaft metadata and log data persist here. The stable `KAFKA_KRAFT_CLUSTER_ID`
combined with volume persistence means Kafka starts cleanly on repeated runs.

---

## Defect Register

| ID | Area | Severity | Description | Action |
|---|---|---|---|---|
| **NEW-1** | Redis cluster init | BLOCKER | `\$` escaping prevents shell variable expansion. Guard never executes. M1 still broken. | Fix required before `make up` |
| M5 | Kafka UI | Non-blocking | `METRICS_PORT: 9092` is wrong (broker port, not JMX). Metrics panel broken. | Deferred Week 2 |
| M23 | Postgres | Informational | `log_line_prefix` on `sales-db` only. Two DBs produce unformatted logs. | Deferred Week 2 |

---

## Required Fix

Apply this minimal two-line change to the `redis-cluster-init` command block:

```diff
-        CLUSTER_STATE=\$(redis-cli \
+        CLUSTER_STATE=$$(redis-cli \

-        if [ \"\\$$CLUSTER_STATE\" = 'ok' ]; then
+        if [ "$$CLUSTER_STATE" = 'ok' ]; then
```

Nothing else changes. The `${REDIS_PASSWORD:-redis_dev}` patterns throughout
remain as single-dollar — those are intentional Compose substitutions.

---

## Post-Fix Verification

```bash
# 1. Validate compose file syntax
docker compose -f deployment/docker/docker-compose.yml config > /dev/null
# Expected: clean exit

# 2. First run
make clean && make up
docker logs flash-sale-sales-db 2>&1 | grep "ready to accept"
# Expected: "database system is ready to accept connections"
docker logs flash-sale-redis-cluster-init | tail -5
# Expected: cluster_state:ok  cluster_known_nodes:6  cluster_size:3

# 3. Idempotency verification — the real M1 test
make up
docker logs flash-sale-redis-cluster-init | grep "Skipping\|already formed"
# Expected: "Cluster already formed (cluster_state:ok). Skipping creation."
# This line on second run = M1 is truly fixed
```

---

*NO-GO on current file. Apply the two-line fix, run the three verification commands,*
*and GO when all three pass.*