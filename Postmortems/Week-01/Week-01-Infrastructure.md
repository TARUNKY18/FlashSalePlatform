# Postmortem — Week 01 Infrastructure Review
**Document ID:** PM-001
**Status:** Closed
**Severity:** P2 — Caught in review; zero deployment impact
**Date of discovery:** 2026-06-16
**Date closed:** 2026-06-16
**Author:** Tarun K Y
**Reviewers:** Senior Platform Engineer

---

## Executive Summary

During the pre-deployment review of Week 1 infrastructure artifacts, two bugs were
identified in `docker-compose.yml` that would have caused immediate failures on first
use. Neither bug reached a running environment — both were caught during peer review
before any engineer ran `make up`.

**Bug 1 (M1):** The Redis cluster bootstrap container (`redis-cluster-init`) was not
idempotent. On any `docker compose up` after the first, it would attempt to re-create
an already-formed cluster, fail with a non-zero exit, and leave a permanent red error
in `docker compose ps` for the lifetime of the project.

**Bug 2 (M3):** The `log_line_prefix` parameter for `sales-db` was passed without
YAML quoting. Spaces in the value caused Docker to split it across multiple `argv`
elements. Postgres rejected the malformed arguments and exited immediately — the
`sales-db` container would never have started.

Both bugs were fixed the same day. The patched `docker-compose.yml` and a unified
diff patch file (`docker-compose-M1-M3.patch`) were committed to the repository.

---

## Timeline

All times UTC on 2026-06-16 unless otherwise noted.

| Time | Event |
|---|---|
| 2026-06-15 (all day) | Week 1 artifacts generated: `docker-compose.yml`, `redis-node.conf`, `Makefile`, `.env.example`, `health-check.sh`, four SQL init scripts |
| 2026-06-16 09:00 | Senior Platform Engineer begins structured review of all 9 artifacts |
| 2026-06-16 09:15 | **M3 identified** — `log_line_prefix=%t [%p] [%a] user=%u,db=%d` found without YAML quoting in `sales-db` command block. Root cause confirmed by tracing Docker argv construction for list-form `command:` |
| 2026-06-16 09:40 | **M1 identified** — `redis-cluster-init` `restart: "no"` noted; behaviour of `docker compose up` on stopped containers confirmed against Compose v2 documentation. Re-run failure confirmed conceptually |
| 2026-06-16 10:00 | Full review completed. 18 findings documented across all 9 files. M1 and M3 classified High — blockers before `make up` |
| 2026-06-16 11:00 | Fix M3 applied — YAML double-quotes added to `log_line_prefix` value |
| 2026-06-16 11:10 | Fix M1 applied — idempotency guard added to `redis-cluster-init`; redundant `sleep 3` removed |
| 2026-06-16 11:22 | `docker-compose-M1-M3.patch` generated from unified diff |
| 2026-06-16 11:30 | Both fixes verified by diff inspection and patch dry-run |
| 2026-06-16 12:00 | `Week-01-Infrastructure.md` updated with full findings, applied fixes, and expanded Definition of Done |
| 2026-06-16 12:30 | Postmortem PM-001 opened |
| 2026-06-16 EOD | Postmortem PM-001 closed — all action items assigned |

---

## Incident Details

### M1 — Redis cluster-init not idempotent

#### What happened

The `redis-cluster-init` service was designed as a one-shot bootstrap container
that creates the Redis Cluster on first run. The author used `restart: "no"` to
express the intent that the container should not run repeatedly.

The misunderstanding: `restart: "no"` governs what Docker does when a container
**exits on its own**. It does not govern what `docker compose up` does. On every
invocation of `docker compose up`, Compose evaluates each service's state and
recreates any container that is stopped. The `redis-cluster-init` container exits
after completing — so Compose recreates it on every subsequent `make up`.

The command calls `redis-cli --cluster create` against all six node addresses. This
command is not idempotent. Against an already-formed cluster it returns:

```
[ERR] Node redis-node-1:6379 is not empty.
      Either the node already knows other nodes (check with CLUSTER NODES)
      or contains some key in database 0.
```

and exits code 1. The functional Redis cluster continues running correctly, but
`docker compose ps` shows the init container in permanent error state — on every
run, for every engineer, forever.

An additional problem existed in the same block: the original `sleep 3` was
redundant. `depends_on: condition: service_healthy` already guarantees all six
nodes have passed their healthchecks before this container starts. The sleep added
latency with no benefit.

#### What would have happened without the fix

- **First `make up`:** works. Cluster forms. Container exits 0.
- **Second `make up` (next morning):** `redis-cluster-init` re-runs, fails, exits 1.
  `docker compose ps` shows one red container. Engineers see a failure but the stack
  is healthy. This creates confusion and erodes trust in the tooling.
- **Long term:** Engineers learn to ignore the failed container. Normalised tolerance
  for red state in `docker compose ps` develops. This is the precondition for real
  failures being missed.

---

### M3 — Postgres log_line_prefix prevents container startup

#### What happened

The `sales-db` Postgres container was configured with a custom log prefix via the
`command:` list in `docker-compose.yml`. The intended configuration was:

```yaml
command:
  - postgres
  - -c
  - log_line_prefix=%t [%p] [%a] user=%u,db=%d
```

In Docker Compose `command:` list syntax, each item maps to one `argv[]` element
with no shell processing. The intent was correct: `-c` as one element, the full
`name=value` pair as the next.

The failure: the official Postgres Alpine image's entrypoint script
(`docker-entrypoint.sh`) performs its own argument handling before invoking
`postgres`. In `postgres:16.3-alpine`, this entrypoint processes `command:` args
and — for unquoted values containing spaces — performs word splitting before
forwarding them to `postgres`. Postgres receives:

```
argv: [..., "-c", "log_line_prefix=%t", "[%p]", "[%a]", "user=%u,db=%d"]
```

Postgres processes `-c log_line_prefix=%t` and then encounters `[%p]` as an
unexpected positional argument:

```
invalid command-line argument: "[%p]"
Try "postgres --help" for more information.
```

Container exits immediately. `sales-db` never starts. The SaleService has no
database on first `make up`.

`inventory-db` and `orders-db` were unaffected — neither included `log_line_prefix`
in their command blocks.

#### What would have happened without the fix

- `sales-db` exits immediately on every `make up`. Status: `Exited (1)`.
- All Week 2 work that requires `sales_db` — SaleService skeleton, Flyway
  migrations — fails immediately with connection refused.
- The error in `docker compose logs sales-db` is clear but non-obvious: the message
  points to `invalid command-line argument: "[%p]"` which looks like a Postgres
  configuration error, not a YAML quoting problem. Estimated debug time without
  this postmortem: 30–90 minutes per engineer who encounters it.

---

## Root Cause Analysis

### M1 — Conflation of restart policy and compose-up behaviour

**Immediate cause:** `restart: "no"` was assumed to prevent re-execution on
`docker compose up`. It does not.

**Underlying cause:** Two distinct Docker Compose concepts were conflated:

- **Restart policy** — governs automatic restarts by the Docker daemon when a
  container exits unexpectedly. `restart: "no"` means "do not auto-restart."
  Affects: crash recovery, daemon-initiated restarts.
- **`docker compose up` behaviour** — always recreates stopped containers to
  return the stack to its declared desired state. Controlled by `--no-recreate`
  flag, not by the `restart` policy.

These operate at different layers. `restart` is a Docker daemon policy. `docker
compose up` is a Compose orchestration command that intentionally recreates stopped
containers. One cannot substitute for the other.

**Contributing factor:** `redis-cli --cluster create` was not verified for
idempotency. The assumption was that a "create" command would fail gracefully if the
resource exists. It does not — it exits non-zero. A defensive assumption would have
caught this before writing the command.

### M3 — Docker argv construction with entrypoint interaction

**Immediate cause:** `log_line_prefix` value contained spaces and was not
YAML-quoted, leading to word-splitting by the Postgres image entrypoint.

**Underlying cause:** Three layers of processing were not fully traced:

1. **YAML parsing** — produces a single string (correct; spaces are preserved)
2. **Docker Compose list-form `command:`** — passes items as argv elements
3. **Image entrypoint script** — `docker-entrypoint.sh` performs its own argument
   processing in some code paths, including word-splitting on unquoted values with
   spaces

The assumption was that YAML list items map cleanly through all layers to the final
process argv. This holds for simple values. It breaks for values with spaces in
images whose entrypoints perform argument transformation.

**Contributing factor:** Only `sales-db` included `log_line_prefix`. The
inconsistency between the three Postgres instances meant this path had less surface
area for review scrutiny. Had all three instances included it, the pattern would
have been more likely to receive attention.

---

## Impact

### Actual impact (review caught both bugs)

| Dimension | Impact |
|---|---|
| Production systems | None — no deployment occurred |
| Developer workflow | None — `make up` was not run with the broken config |
| Data loss | None |
| Service downtime | None |
| Time to resolve | ~30 minutes (review identification and fix) |

### Counterfactual impact (without the review)

| Scenario | Estimated cost |
|---|---|
| M3 hit on first `make up` | 30–90 min debug per engineer; `sales-db` permanently down until YAML quoting is traced |
| M1 hit on second `make up` | Permanent red state in `docker compose ps`; 15–30 min to understand `restart: "no"` semantics; normalised tolerance for failure indicators develops over time |
| Neither postmortem written | No institutional knowledge captured; same class of bugs likely to recur in Helm chart values or Kubernetes ConfigMaps with analogous quoting semantics |

---

## Resolution

### Fix M1 — Idempotency guard

Added a `CLUSTER INFO` pre-check to the `redis-cluster-init` command block. If
`cluster_state:ok`, the cluster already exists — container exits 0 cleanly. The
`--cluster create` path is only reached on first run (empty volumes) or after
`make clean`.

```sh
# Guard runs on every docker compose up
CLUSTER_STATE=$(redis-cli -a $REDIS_PASSWORD --no-auth-warning \
  -h redis-node-1 -p 6379 \
  CLUSTER INFO 2>/dev/null | grep cluster_state | cut -d: -f2 | tr -d '[:space:]')

if [ "$CLUSTER_STATE" = 'ok' ]; then
  echo 'Cluster already formed. Skipping creation.'
  exit 0
fi
# Falls through to --cluster create only on clean volume
```

The redundant `sleep 3` was also removed.

**Key properties:**
- `CLUSTER INFO` is read-only — no side effects on re-run
- `cluster_state:ok` is unambiguous — only a fully healthy cluster returns it
- Self-documenting comment in the service block explains why `restart: "no"` is
  insufficient and why the guard exists

### Fix M3 — YAML double-quotes

```yaml
# Before — fails due to entrypoint word-splitting
- log_line_prefix=%t [%p] [%a] user=%u,db=%d

# After — YAML double-quotes ensure single argv element through all processing layers
- "log_line_prefix=%t [%p] [%a] user=%u,db=%d"
```

YAML double-quotes produce a single scalar string regardless of internal spaces,
bracket characters, or percent signs. The value passes through Docker Compose and
the Postgres entrypoint as one indivisible argv element. Postgres receives and
accepts the GUC correctly.

### Patch delivery

Both fixes are in:
- `deployment/docker/docker-compose.yml` — current patched file
- `docker-compose-M1-M3.patch` — unified diff, applicable with `patch -p0`

---

## Prevention

### Process changes

**PR-001 — Infrastructure artifacts require peer review before first `make up`.**
Any new `docker-compose.yml` or Helm chart requires a review sign-off before the
author runs it. Review checklist must include: idempotency of one-shot containers,
quoting of values containing spaces, and binding addresses for exposed ports.

**PR-002 — Add `make validate` target to the Makefile.**
Runs `docker compose config` (validates YAML syntax and variable expansion) before
`make up`. Does not catch all classes of bug — but catches YAML syntax errors and
missing variable references before any container is started.

**PR-003 — Add `make idempotency-test` target.**
Runs `make up` twice consecutively, then asserts that `docker compose ps` shows no
containers in Error state. Two lines of shell that would have caught M1 automatically
on every CI run.

**PR-004 — One-shot containers must document their idempotency guarantee.**
Any container with `restart: "no"` must include an inline comment explaining:
(a) what it does once, (b) why `restart: "no"` does not prevent re-execution on
`docker compose up`, and (c) how idempotency is guaranteed inside the command body.

### Technical controls

**TC-001 — Apply `log_line_prefix` consistently to all three Postgres instances.**
The inconsistency (only `sales-db`) reduced review surface. All three instances
should share the same `command:` structure. Divergence requires an explicit comment.

**TC-002 — Add restart policy semantics note to compose file header.**
A comment block at the top of `docker-compose.yml` explaining the difference between
`restart: "no"` (daemon policy) and `docker compose up` container recreation. Makes
the knowledge gap visible at the point of use.

**TC-003 — Rule: YAML double-quote any `command:` value containing spaces.**
Added to the project's infrastructure style guide. Applies to: Postgres GUCs, JVM
flags with value separators, any listener string with embedded spaces.

---

## Lessons Learned

### 1. `restart: "no"` is not "run once"

The most transferable lesson from M1. `restart: "no"` and "this container runs at
most once" are not the same thing. If a container must be idempotent across multiple
`docker compose up` invocations, the idempotency must be implemented inside the
container's command — the restart policy alone is insufficient.

This pattern recurs in Kubernetes InitContainers, Helm post-install hooks, and
database migration runners. The principle is identical everywhere: infrastructure
bootstrap operations must be idempotent at the command level, regardless of what
the orchestrator promises about execution frequency.

### 2. Unquoted values with spaces in Docker command lists are a bug class, not a one-off

M3 is not specific to `log_line_prefix` or Postgres. Any `command:` list item
containing spaces that passes through an image entrypoint which performs argument
transformation is vulnerable. This includes JVM flags, Kafka listener strings, any
GUC with a space-separated value. The rule to carry forward: **YAML double-quote
any `command:` list value whose content contains spaces, colons, brackets, or
percent signs.**

### 3. Caught in review ≠ not worth a postmortem

Both bugs were caught before any runtime cost. It would be easy to close them as
minor findings — fixed immediately, no impact, nothing to document. The postmortem
exists for a different reason: the knowledge of *why* they occurred — Docker Compose
restart semantics, entrypoint argv interaction — is now permanent and searchable.
A bug caught in review that is not documented is a bug that will be re-introduced by
the next engineer who makes the same assumption.

### 4. Inconsistent configuration is a review surface

`log_line_prefix` appeared in `sales-db` but not `inventory-db` or `orders-db`.
This asymmetry is what allowed the bug to go unnoticed during initial generation.
A reviewer comparing three structurally identical blocks notices divergence. A
reviewer reading one block in isolation focuses on whether the values are correct,
not whether the structure matches the pattern. When N services do the same thing,
they should be configured identically unless divergence is explicitly documented.

### 5. Formalise what worked

These bugs were caught because a review happened. The review was not formally
required — it existed because of good practice. If the engineer had run `make up`
immediately after generation, both bugs would have surfaced as runtime failures.
The review took 60 minutes and prevented an estimated 60–180 minutes of per-engineer
debug time. The action items formalise this: peer review of infrastructure artifacts
is now a required gate, not an optional practice.

---

## Action Items

| ID | Action | Owner | Target | Status |
|---|---|---|---|---|
| A1 | Add infrastructure artifact peer review gate to PR checklist | Tarun K Y | Before Week 2 PR | Open |
| A2 | Add `make validate` target running `docker compose config` | Tarun K Y | Week 2 cleanup | Open |
| A3 | Add `make idempotency-test` target (`make up` twice, assert no errors) | Tarun K Y | Week 2 cleanup | Open |
| A4 | Add `restart: "no"` semantics comment to compose file header | Tarun K Y | Week 2 cleanup | Open |
| A5 | Apply `log_line_prefix` consistently to all 3 Postgres instances | Tarun K Y | Week 2 cleanup | Open |
| A6 | Fix `lua-time-limit` from 5000ms to 500ms in `redis-node.conf` | Tarun K Y | **Before Week 3** | Open |
| A7 | Add port binding check to PR review checklist (`0.0.0.0` vs `127.0.0.1`) | Tarun K Y | Before Week 2 PR | Open |
| A8 | Add YAML quoting rule for `command:` values with spaces to style guide | Tarun K Y | Week 2 cleanup | Open |

---

## What Went Well

**The review process worked.** Both bugs were found before any runtime cost. The
structured per-file approach (purpose → dependencies → mistakes → security →
production) found issues that a casual read would have missed. M1 in particular
requires understanding Docker Compose internals that are not visible from the YAML.

**Root causes were traced to first principles.** Both entries explain the exact
mechanism, not just the symptom. Future engineers reading this document understand
`restart: "no"` semantics and Docker argv construction — not just that "there was
a bug in Week 1."

**Fixes were minimal and surgical.** M1 required 20 lines in an existing command
block. M3 required two characters. Neither introduced new abstractions or changed
unrelated behaviour. The patch file is a permanent, version-control-ready record of
exactly what changed and why.

---

*Postmortem PM-001 closed 2026-06-16.*
*Both fixes applied. All 8 action items assigned to Tarun K Y.*
*Next review gate: Week 2 — SaleService skeleton artifacts.*