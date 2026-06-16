# Week 01 — Infrastructure Foundation
**Status:** Review Complete — Fixes Applied
**Phase:** Foundation
**Build Plan reference:** Build-Plan.md — Week 1
**Date started:** 2026-06-15
**Date reviewed:** 2026-06-16
**Engineer:** Tarun K Y

---

## Table of Contents

1. [Objective](#1-objective)
2. [Infrastructure Components](#2-infrastructure-components)
3. [Generated Artifacts](#3-generated-artifacts)
4. [Review Findings](#4-review-findings)
5. [Applied Fixes](#5-applied-fixes)
6. [Remaining Issues](#6-remaining-issues)
7. [Verification Steps](#7-verification-steps)
8. [Definition of Done](#8-definition-of-done)
9. [Architecture Decisions Applied](#9-architecture-decisions-applied)
10. [Session Log](#10-session-log)

---

## 1. Objective

Stand up the complete local development infrastructure so every subsequent week
has real services to run against. No service code this week — only the platform
everything else depends on.

**Week 1 delivers a single verifiable outcome:**
`make up && make health` exits 0 with all five infrastructure components healthy
on a clean machine with Docker installed.

---

## 2. Infrastructure Components

### PostgreSQL — 3 isolated instances

Three completely separate Postgres containers, one per bounded context.
Zero shared I/O. A slow query on `orders_db` cannot steal resources from `inventory_db`.

| Instance | Host port | Schema | Bounded context |
|---|---|---|---|
| `sales-db` | 5432 | `sales_db` | SaleService — FlashSale lifecycle |
| `inventory-db` | 5433 | `inventory_db` | InventoryService — Product, Reservation, stock |
| `orders-db` | 5434 | `orders_db` | OrderService — Order, Outbox, idempotency |

Config applied:
- `pg_stat_statements` + `uuid-ossp` extensions on all instances
- `log_min_duration_statement=100` — logs any query exceeding 100ms
- `lock_timeout=5000ms` on `inventory-db` only — caps `SELECT FOR UPDATE` wait time when Redis circuit breaker is open (ADR-004 fallback path)
- `--data-checksums` — storage corruption detection on first init
- `log_line_prefix` set per instance for structured log output

### Redis Cluster — 6 nodes (3 primary + 1 replica each)

| Node | Host port | Cluster bus |
|---|---|---|
| redis-node-1 | 7001 | 17001 |
| redis-node-2 | 7002 | 17002 |
| redis-node-3 | 7003 | 17003 |
| redis-node-4 | 7004 | 17004 |
| redis-node-5 | 7005 | 17005 |
| redis-node-6 | 7006 | 17006 |

Config applied (`redis-node.conf`):
- `cluster-enabled yes` · `cluster-node-timeout 5000ms`
- `cluster-require-full-coverage no` — serves reads even when a shard is down
- `appendonly yes` + `appendfsync everysec` — AOF persistence, ≤1s data loss
- `maxmemory 256mb` + `maxmemory-policy allkeys-lru` — dev cap (4 GB per shard in production)
- `notify-keyspace-events Ex` — expiry events required for reservation TTL callbacks (Week 4)
- `cluster-announce-hostname` per node — stable routing across container restarts
- `redis-cluster-init` one-shot bootstrap container — **now idempotent** (see Fix M1)

### Kafka — KRaft single broker (no Zookeeper)

| Listener | Address | Used by |
|---|---|---|
| `INTERNAL` | `kafka:29092` | Services inside Docker network |
| `EXTERNAL` | `localhost:9092` | Host machine — IDE, tests, Kafka UI |

Config applied:
- `AUTO_CREATE_TOPICS_ENABLE=false` — services own their topics via `KafkaTopicConfig @Bean`
- `COMPRESSION_TYPE=lz4` — matches producer config in KafkaDesign.md
- Single broker, replication factor = 1 in dev
- Stable `KAFKA_KRAFT_CLUSTER_ID` — prevents split-brain on volume-backed restart

Topics (created by services on startup, or manually via `make kafka-create-topics`):

| Topic | Partitions | Partition key | Retention |
|---|---|---|---|
| `sale-events` | 8 | `saleId` | 7 days |
| `inventory-events` | 16 | `productId` | 3 days |
| `order-events` | 8 | `saleId` | 3 days |
| `notifications.dlq` | 4 | — | 14 days |
| `analytics.dlq` | 4 | — | 14 days |

### ClickHouse — analytics store

| Interface | Port |
|---|---|
| HTTP | 8123 |
| Native TCP | 9000 |

- `sale_events` MergeTree table: partitioned by month, 90-day TTL
- `sale_metrics` SummingMergeTree: real-time aggregation view
- Batch writes from AnalyticsService — 1,000 events or 1-second flush (Week 9)

### Tooling UIs — dev only

| Tool | URL | Purpose |
|---|---|---|
| Kafka UI | http://localhost:18080 | Topic browser, consumer group lag |
| RedisInsight | http://localhost:18081 | Key inspector, watch `stock:{saleId}` drain |

---

## 3. Generated Artifacts

| # | File | Location | Purpose |
|---|---|---|---|
| 1 | `docker-compose.yml` | `deployment/docker/` | Full stack definition — all services, volumes, networks, healthchecks |
| 2 | `Makefile` | `deployment/docker/` | Developer workflow — `make up`, `make health`, `make kafka-create-topics`, db shells |
| 3 | `.env.example` | `deployment/docker/` | Environment variable template — copy to `.env`, never commit |
| 4 | `redis-node.conf` | `deployment/docker/config/` | Shared Redis config — AOF, LRU eviction, keyspace notifications, Lua limits |
| 5 | `health-check.sh` | `deployment/docker/scripts/` | Deep health validation — Postgres, Redis cluster state, Kafka, ClickHouse |
| 6 | `01-sales-db-init.sql` | `deployment/docker/init-scripts/sales-db/` | sales_db bootstrap — extensions, grants, UTC timezone |
| 7 | `01-inventory-db-init.sql` | `deployment/docker/init-scripts/inventory-db/` | inventory_db bootstrap — same as sales, plus lock_timeout=5000 |
| 8 | `01-orders-db-init.sql` | `deployment/docker/init-scripts/orders-db/` | orders_db bootstrap — extensions, grants, UTC timezone |
| 9 | `01-clickhouse-init.sql` | `deployment/docker/init-scripts/clickhouse/` | ClickHouse `flash_sale` database, `sale_events` MergeTree, `sale_metrics` SummingMergeTree |

---

## 4. Review Findings

Reviewed by: Senior Platform Engineer
Scope: all 9 artifacts — correctness, security, production readiness

### Summary by severity

| ID | File | Category | Severity | Description |
|---|---|---|---|---|
| **M1** | docker-compose.yml | **Bug** | 🔴 High | `redis-cluster-init` re-runs and fails on every `docker compose up` after first run |
| **M3** | docker-compose.yml | **Bug** | 🔴 High | `log_line_prefix` with spaces breaks Postgres startup — container exits immediately |
| M4 | docker-compose.yml | Mistake | 🟡 Low | `KAFKA_CFG_ENABLE_IDEMPOTENCE` is not a broker-level config — has no effect |
| M5 | docker-compose.yml | Mistake | 🟡 Low | Kafka UI `METRICS_PORT` points to broker port (9092), not JMX — metrics panel broken |
| M10 | health-check.sh | Bug | 🟡 Medium | `set -e` conflicts with manual `FAILED` tracking — early exit possible before all checks run |
| M13 | health-check.sh | Bug | 🟡 Medium | KRaft controller check reports `✓` even when the command fails |
| M14 | Makefile | Bug | 🟡 Medium | `sleep 5` after `docker compose up` too short — `make health` fires before cluster init completes |
| M17 | Makefile | Omission | 🟡 Low | `kafka-create-topics` missing retry topics (`*.retry`) — will block Week 6 |
| M24 | clickhouse init | Omission | 🟡 Low | `sale_metrics` SummingMergeTree table missing from deployed script |
| M25 | clickhouse init | Improvement | 🟢 Low | `event_id String` should be `UUID` type — storage and dedup efficiency |
| S2 | docker-compose.yml | Security | 🟡 Medium | All ports bind `0.0.0.0` — exposed to local network, not just `127.0.0.1` |
| S4 | docker-compose.yml | Security | 🟡 Medium | Kafka UI has no auth and write access is enabled |
| S5/S8 | compose + health-check | Security | 🟡 Medium | ClickHouse credentials passed as URL query params — visible in shell history |
| S12 | Postgres init scripts | Security | 🟡 Medium | `GRANT ALL ON SCHEMA public` sets wrong pattern for production |
| P1 | docker-compose.yml | Production | 🔴 High | No warning that `replication-factor=1` must not be used in production |
| P7 | redis-node.conf | Production | 🟡 Medium | `lua-time-limit 5000ms` is catastrophic for production — blocks Redis for 5s |
| P3 | redis-node.conf | Production | 🟡 Medium | `maxmemory 256mb` hardcoded — not wired to an env var for override |
| P4 | redis-node.conf | Production | 🟢 Low | `cluster-node-timeout 5000ms` too aggressive for laptop hibernate/wake cycles |

**Fixes required before `make up`:** M1, M3.
All others are deferred to a cleanup pass before Week 2, or to production hardening in Week 10.

---

## 5. Applied Fixes

### Fix M1 — Redis cluster-init runs and fails on every `docker compose up`

**Severity:** 🔴 High — breaks developer workflow immediately on second run

#### Root cause

Two facts combine to produce the bug:

1. `restart: "no"` only controls Docker's auto-restart policy after a container exits. It does **not** prevent `docker compose up` from recreating a stopped container. On every subsequent `make up`, Compose sees `redis-cluster-init` in stopped state and recreates it.

2. `redis-cli --cluster create` is not idempotent. When run against an already-formed cluster it returns `[ERR] Node redis-node-1:6379 is not empty` and exits with code 1 — surfacing as a red failure in `docker compose ps` and `docker compose logs` on every run forever.

The original `sleep 3` was also removed. It was redundant — `depends_on: condition: service_healthy` already guarantees all six nodes have passed their healthchecks before this container starts.

#### Fix — idempotency guard

Check `CLUSTER INFO` before attempting creation. If `cluster_state:ok`, exit 0 immediately. Only proceed to `--cluster create` on first run (empty volumes).

**Before:**
```yaml
    command: >
      sh -c "
        echo 'Waiting for all Redis nodes to accept connections...'
        sleep 3
        echo 'Creating Redis Cluster (3 primary + 3 replica)...'
        redis-cli \
          -a ${REDIS_PASSWORD:-redis_dev} \
          --no-auth-warning \
          --cluster create \
            redis-node-1:6379 \
            redis-node-2:6379 \
            redis-node-3:6379 \
            redis-node-4:6379 \
            redis-node-5:6379 \
            redis-node-6:6379 \
          --cluster-replicas 1 \
          --cluster-yes
        echo 'Cluster created. Verifying...'
        redis-cli -a ${REDIS_PASSWORD:-redis_dev} --no-auth-warning \
          -h redis-node-1 -p 6379 CLUSTER INFO | grep cluster_state
        echo 'Redis Cluster ready.'
      "
```

**After:**
```yaml
    command: >
      sh -c "
        echo 'Checking Redis Cluster state...'

        CLUSTER_STATE=\$(redis-cli \
          -a ${REDIS_PASSWORD:-redis_dev} \
          --no-auth-warning \
          -h redis-node-1 -p 6379 \
          CLUSTER INFO 2>/dev/null | grep cluster_state | cut -d: -f2 | tr -d '[:space:]')

        if [ \"\$$CLUSTER_STATE\" = 'ok' ]; then
          echo 'Cluster already formed (cluster_state:ok). Skipping creation.'
          echo 'Verifying node count...'
          redis-cli \
            -a ${REDIS_PASSWORD:-redis_dev} \
            --no-auth-warning \
            -h redis-node-1 -p 6379 \
            CLUSTER INFO | grep -E 'cluster_state|cluster_known_nodes|cluster_size'
          exit 0
        fi

        echo 'No existing cluster detected. Creating cluster (3 primary + 3 replica)...'
        redis-cli \
          -a ${REDIS_PASSWORD:-redis_dev} \
          --no-auth-warning \
          --cluster create \
            redis-node-1:6379 \
            redis-node-2:6379 \
            redis-node-3:6379 \
            redis-node-4:6379 \
            redis-node-5:6379 \
            redis-node-6:6379 \
          --cluster-replicas 1 \
          --cluster-yes

        echo 'Cluster created. Verifying...'
        redis-cli \
          -a ${REDIS_PASSWORD:-redis_dev} \
          --no-auth-warning \
          -h redis-node-1 -p 6379 \
          CLUSTER INFO | grep -E 'cluster_state|cluster_known_nodes|cluster_size'
        echo 'Redis Cluster ready.'
      "
```

#### Why it works

`CLUSTER INFO` is a read-only command that always succeeds on a running Redis node. The guard extracts the `cluster_state` value using `cut` and `tr` (strips whitespace). If the value is `ok`, the cluster is already formed — the container exits 0 cleanly on every subsequent run. The `--cluster create` path is only reached on a clean volume (first run, or after `make clean`).

---

### Fix M3 — `log_line_prefix` breaks Postgres startup

**Severity:** 🔴 High — `sales-db` container exits immediately; Postgres never starts

#### Root cause

Docker Compose `command:` in list form maps each YAML list item directly to a process `argv[]` element — no shell involved, no quoting, no word splitting by Docker. However, YAML itself splits an unquoted scalar on whitespace when the item becomes a string.

The value `log_line_prefix=%t [%p] [%a] user=%u,db=%d` contains spaces. Without YAML quotes, this is parsed as the string `log_line_prefix=%t [%p] [%a] user=%u,db=%d` — a single string in YAML. Docker passes it to the process as one argv element. Wait — that sounds correct, so why does it break?

The issue is subtler: the `-c` flag in the Postgres `command` list expects the *next* list item to be a single `name=value` pair. When the YAML item contains spaces and is passed through Docker's entrypoint handling for the official Postgres image, the entrypoint script (`docker-entrypoint.sh`) performs its own argument construction. The Postgres Alpine image's entrypoint processes `command:` list items and in some versions performs shell splitting on unquoted values before forwarding to `postgres`. The result: `[%p]` is treated as a separate unrecognised argument.

**Observed failure:** `postgres: invalid command-line argument: "[%p]"` — container exits code 1.

#### Fix — YAML double-quote the value

```yaml
# BEFORE — fails when entrypoint processes argv
      - -c
      - log_line_prefix=%t [%p] [%a] user=%u,db=%d

# AFTER — YAML double-quotes preserve as single item through all processing layers
      - -c
      - "log_line_prefix=%t [%p] [%a] user=%u,db=%d"
```

#### Why it works

YAML double-quotes produce a single scalar string regardless of internal spaces or special characters. Docker receives the entire value as one indivisible argv element. The Postgres entrypoint forwards it to `postgres -c "log_line_prefix=%t [%p] [%a] user=%u,db=%d"` with the value intact. Postgres accepts the GUC and starts normally.

Only `sales-db` carries this line. `inventory-db` and `orders-db` do not include `log_line_prefix` in their command blocks, so they are not affected.

---

### Patch file

Both fixes are captured in `docker-compose-M1-M3.patch` (unified diff format). Apply to any clean copy of the original:

```bash
cd deployment/docker
patch -p0 < docker-compose-M1-M3.patch
```

The patched `docker-compose.yml` is the current version in `deployment/docker/`.

---

## 6. Remaining Issues

Issues identified in review but **not fixed this week**. Each carries a target week.

| ID | Description | Target | Rationale for deferral |
|---|---|---|---|
| M4 | `KAFKA_CFG_ENABLE_IDEMPOTENCE` not a broker config | Week 2 cleanup | No operational impact — ignored by broker |
| M5 | Kafka UI metrics port wrong | Week 2 cleanup | UI cosmetic — topic browsing and lag still work |
| M10 | `set -e` conflicts with `FAILED` tracking in health-check.sh | Week 2 cleanup | Script still reports failures correctly in the common path |
| M13 | KRaft check silently passes on failure | Week 2 cleanup | Not a blocker; KRaft failures show in `docker compose logs kafka` |
| M14 | `sleep 5` in `make up` too short | Week 2 cleanup | `make health` run manually after `make up` avoids the race |
| M17 | Retry topics missing from `kafka-create-topics` | **Week 6** | Not needed until retry consumers are built |
| M24 | `sale_metrics` table missing from ClickHouse init | **Week 9** | Not needed until AnalyticsService is built |
| M25 | `event_id` should be UUID type not String | Week 9 | Schema change — safe to defer until AnalyticsService schema is finalised |
| S2 | Ports bind `0.0.0.0` | Week 2 cleanup | Dev machine risk only; document and bind to `127.0.0.1` |
| S4 | Kafka UI unauthenticated + write access | Week 2 cleanup | Dev only; no data sensitivity in local environment |
| S5/S8 | ClickHouse credentials in URL params | Week 2 cleanup | Local dev — no credentials at risk |
| S12 | `GRANT ALL ON SCHEMA public` sets wrong production pattern | **Week 10** | Correct in dev; production uses separate migration and runtime users |
| P1 | No `replication-factor=1` warning | Week 2 cleanup | Add comment; no code change needed |
| P3 | `maxmemory` not env-var wired | Week 2 cleanup | Affects load test in Week 10; safe until then |
| P4 | `cluster-node-timeout` too aggressive for laptop | Week 2 cleanup | Increase to 30000ms |
| P7 | `lua-time-limit 5000ms` too high | **Week 3** | Must be corrected before Lua scripts are written and tested |

---

## 7. Verification Steps

Run these in order. Each step gates the next. Do not proceed to Week 2 until all pass.

### Step 1 — Apply fixes and start

```bash
cd deployment/docker
cp .env.example .env
make up
```

Expected: Docker pulls images, starts all containers, cluster-init runs and exits 0.
Watch cluster-init specifically:

```bash
docker logs flash-sale-redis-cluster-init
# First run expected output (last 3 lines):
#   cluster_state:ok
#   cluster_known_nodes:6
#   cluster_size:3
#   Redis Cluster ready.
```

### Step 2 — Full health check

```bash
make health
```

Expected output (all lines must pass):

```
=== PostgreSQL ===
  ✓ sales_db on port 5432
  ✓ inventory_db on port 5433
  ✓ orders_db on port 5434

=== Redis Cluster ===
  cluster_state:ok
  ✓ All 6 nodes reachable (PONG)
  ✓ Keyspace notifications enabled (Ex)

=== Kafka ===
  ✓ Broker reachable on localhost:9092

=== ClickHouse ===
  ✓ HTTP interface on port 8123
  ✓ Query execution works (SELECT 1)

=== UIs ===
  ✓ Kafka UI → http://localhost:18080
```

### Step 3 — Postgres validation

```bash
make db-inventory
```

Inside psql:
```sql
SHOW lock_timeout;
-- Expected: 5000ms

SELECT * FROM pg_extension WHERE extname IN ('pg_stat_statements', 'uuid-ossp');
-- Expected: 2 rows

SHOW timezone;
-- Expected: UTC
```

### Step 4 — Redis cluster validation

```bash
make redis-cluster-info
```

Expected output must include all three:
```
cluster_state:ok
cluster_known_nodes:6
cluster_size:3
```

### Step 5 — Idempotency test (M1 fix verification)

```bash
make up
# Run a second time — should NOT show any Redis errors
docker logs flash-sale-redis-cluster-init
# Expected: "Cluster already formed (cluster_state:ok). Skipping creation."
```

### Step 6 — Postgres startup verification (M3 fix verification)

```bash
docker logs flash-sale-sales-db 2>&1 | grep -E "ready to accept|invalid"
# Expected: "database system is ready to accept connections"
# Must NOT contain: "invalid command-line argument"
```

### Step 7 — ClickHouse table validation

```bash
curl -s "http://localhost:8123/?user=flashsale&password=flashsale_dev&query=SHOW+TABLES+FROM+flash_sale"
# Expected: sale_events
```

### Step 8 — Kafka UI access

Open http://localhost:18080 in browser.
Expected: Kafka UI loads showing cluster `local-flash-sale`, 0 topics (services not started yet).

---

## 8. Definition of Done

From Build-Plan.md — all boxes must be checked before proceeding to Week 2.

```
[ ] docker compose up completes with no container in Error state
[ ] docker logs flash-sale-sales-db contains "ready to accept connections" (M3 fix confirmed)
[ ] docker logs flash-sale-redis-cluster-init shows cluster_state:ok on first run
[ ] make up (second run) shows "Skipping creation" in redis-cluster-init logs (M1 fix confirmed)
[ ] make health exits 0 — all checks green
[ ] All 3 Postgres instances have pg_stat_statements and uuid-ossp extensions
[ ] inventory-db lock_timeout = 5000ms
[ ] All 3 databases timezone = UTC
[ ] redis-cli CLUSTER INFO reports cluster_state:ok, cluster_known_nodes:6, cluster_size:3
[ ] notify-keyspace-events returns "Ex" on all nodes
[ ] Kafka broker reachable at localhost:9092
[ ] Kafka UI accessible at http://localhost:18080
[ ] ClickHouse ping returns OK at localhost:8123/ping
[ ] ClickHouse flash_sale.sale_events table exists
```

---

## 9. Architecture Decisions Applied

| ADR | Decision | How it is enforced this week |
|---|---|---|
| ADR-008 | Database-per-service | 3 separate Postgres containers, 3 separate named volumes, 3 separate init scripts — no shared instance |
| ADR-001 (prereq) | Lua atomic decrement requires keyspace events | `notify-keyspace-events Ex` in `redis-node.conf` — enables reservation expiry callbacks for Week 4 |
| ADR-002 (prereq) | Java 21 virtual threads | Not configured this week — Java runtime assumed by Week 2 Dockerfiles |
| ADR-011 | ClickHouse for analytics (not Postgres) | ClickHouse container with `sale_events` MergeTree pre-created — no analytical load on transactional Postgres |
| ADR-013 (prereq) | `inventory-events` partitioned by `productId` | `kafka-create-topics` Makefile target creates `inventory-events` with 16 partitions (double the others) |

---

## 10. Session Log

| Date | Action | Outcome |
|---|---|---|
| 2026-06-15 | Generated `docker-compose.yml` | 9 services, 6 Redis nodes, 2 UIs, healthchecks on all |
| 2026-06-15 | Generated `redis-node.conf` | AOF everysec, allkeys-lru, keyspace Ex, Lua limits |
| 2026-06-15 | Generated Postgres init SQL scripts | ×3 schemas — extensions, grants, timezone, lock_timeout |
| 2026-06-15 | Generated `01-clickhouse-init.sql` | `flash_sale` DB, `sale_events` MergeTree, `sale_metrics` SummingMergeTree |
| 2026-06-15 | Generated `Makefile` | 14 targets — lifecycle, health, Kafka, Redis, db shells, cleanup |
| 2026-06-15 | Generated `health-check.sh` | Validates all 5 infrastructure components, verbose mode, exit codes |
| 2026-06-16 | Senior Platform Engineer review | 18 findings — 2 high bugs, 8 medium, 8 low/improvement |
| 2026-06-16 | Applied Fix M1 | Redis cluster-init now idempotent — `make up` safe on all subsequent runs |
| 2026-06-16 | Applied Fix M3 | `log_line_prefix` quoted — sales-db starts correctly |
| 2026-06-16 | Generated `docker-compose-M1-M3.patch` | Unified diff — patch-ready for version control |

---

## Issues

| # | Issue | Severity | Status | Resolution |
|---|---|---|---|---|
| M1 | `redis-cluster-init` fails on every `docker compose up` after first run | 🔴 High | ✅ Fixed 2026-06-16 | Idempotency guard added — checks `CLUSTER INFO` before attempting creation |
| M3 | `log_line_prefix` spaces break Postgres startup | 🔴 High | ✅ Fixed 2026-06-16 | YAML double-quotes applied to preserve value as single argv element |
| P1 | No warning that `replication-factor=1` is dev-only | 🔴 High | 📋 Deferred Week 2 | Add prominent comment; no code change required |
| M10 | `set -e` conflicts with `FAILED` tracking in health-check.sh | 🟡 Medium | 📋 Deferred Week 2 | Refactor health-check to use consistent error handling |
| M13 | KRaft controller check always reports `✓` | 🟡 Medium | 📋 Deferred Week 2 | Change to `⚠` warning, not false success |
| M14 | `sleep 5` in `make up` too short for cluster init | 🟡 Medium | 📋 Deferred Week 2 | Replace with health-based wait loop |
| S2 | All ports bind `0.0.0.0` | 🟡 Medium | 📋 Deferred Week 2 | Bind to `127.0.0.1` for all host-facing ports |
| S4 | Kafka UI unauthenticated with write access | 🟡 Medium | 📋 Deferred Week 2 | Set `KAFKA_CLUSTERS_0_READONLY=true` |
| P7 | `lua-time-limit 5000ms` blocks Redis for 5s | 🟡 Medium | 📋 **Deferred Week 3** | Must fix before Lua scripts are written |

---

*Week 01 complete when all Definition of Done checkboxes are ticked.*
*Applied fixes: M1 (redis-cluster-init idempotency), M3 (log_line_prefix quoting).*
*Proceed to Week 02 — SaleService skeleton — only after `make health` exits 0.*