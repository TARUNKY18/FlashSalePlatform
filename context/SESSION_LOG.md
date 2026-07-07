# SESSION_LOG.md
## Flash Sale Platform — Engineering Session Log
**Rule:** Append-only. Entries are never edited after they are written.
**Purpose:** Human audit trail. Not read by agents at session start — agents read
`PROJECT_TRUTH.md`, `CURRENT_STATE.md`, `CONFLICTS.md`, `REPOSITORY_INDEX.md`.

---

## SESSION-001
**Date:** 2026-06-17
**Milestone:** Week 1 — Infrastructure Foundation
**Outcome:** COMPLETE
**Engineer:** Tarun K Y

---

### Objective

Stand up the full local development infrastructure stack: three PostgreSQL instances,
Redis Cluster (6 nodes), Apache Kafka (KRaft), ClickHouse, Kafka UI, and RedisInsight.
Confirm `make health` exits 0 with all five components green. Do not write any Java code.

---

### Phase 1 — Architecture design (pre-implementation)

Full architecture was designed and documented before any infrastructure was written.
The following documents were generated and are planned for placement in `docs/architecture/`
and `docs/adr/` — placement not yet confirmed in the repository.

**Architecture documents generated (PLANNED placement):**
- `Final-Spec-Council.md` — definitive architecture specification
- `DomainModel.md` — 4 aggregate roots, bounded contexts, ACL definition
- `DatabaseSchema.md` — schema narrative for all three databases
- `schema.sql` — DDL for `sales_db`, `inventory_db`, `orders_db`
- `KafkaDesign.md` — topic topology, partition strategy, consumer groups
- `RedisDesign.md` — key/layer design, eviction, persistence
- `PRD-FlashSalePlatform.md` — product requirements, NFRs, acceptance criteria
- `Build-Plan.md` — 10-week roadmap, 38 tasks

**ADR document generated:**
- `docs/adr/01-Decisions.md` — 19 architecture decision records, all APPROVED

**Source document precedence order established (in AI-CONTEXT.md):**
```
1. README.md
2. PRD-FlashSalePlatform.md
3. Final-Spec-Council.md
4. ADRs (01-Decisions.md)
5. DomainModel.md
6. DatabaseSchema.md
7. schema.sql
```
`RedisDesign.md`, `KafkaDesign.md`, `Build-Plan.md` classified as unranked
supplementary documents.

---

### Phase 2 — Infrastructure implementation

**Files created (all VERIFIED existing in repository):**

| File | Location |
|---|---|
| `docker-compose.yml` | `deployment/docker/` |
| `.env` | `deployment/docker/` |
| `.env.example` | `deployment/docker/` |
| `redis-node.conf` | `deployment/docker/config/` |
| `init-scripts/sales-db/` | `deployment/docker/init-scripts/` |
| `init-scripts/inventory-db/` | `deployment/docker/init-scripts/` |
| `init-scripts/orders-db/` | `deployment/docker/init-scripts/` |
| `init-scripts/clickhouse/01-init.sql` | `deployment/docker/init-scripts/clickhouse/` |
| `health-check.sh` | `deployment/docker/scripts/` |
| `Makefile` | `FlashSalePlatform/` (root — not in deployment/docker/) |

**Container inventory (14 total — VERIFIED by `make up` output):**

| Container | Image | Host port |
|---|---|---|
| flash-sale-sales-db | postgres:16.3-alpine | 5432 |
| flash-sale-inventory-db | postgres:16.3-alpine | 5433 |
| flash-sale-orders-db | postgres:16.3-alpine | 5434 |
| flash-sale-redis-1 | redis:7.2.5-alpine | 7001 |
| flash-sale-redis-2 | redis:7.2.5-alpine | 7002 |
| flash-sale-redis-3 | redis:7.2.5-alpine | 7003 |
| flash-sale-redis-4 | redis:7.2.5-alpine | 7004 |
| flash-sale-redis-5 | redis:7.2.5-alpine | 7005 |
| flash-sale-redis-6 | redis:7.2.5-alpine | 7006 |
| flash-sale-redis-cluster-init | redis:7.2.5-alpine | — (one-shot) |
| flash-sale-kafka | apache/kafka:3.7.0 | 9092 |
| flash-sale-clickhouse | clickhouse-server:24.3.3-alpine | 8123 / 19000 |
| flash-sale-kafka-ui | kafka-ui:v0.7.2 | 18080 |
| flash-sale-redisinsight | redisinsight:2.50 | 18081 |

---

### Phase 3 — Pre-deployment bug discovery (6 bugs fixed before any container ran)

All six bugs were found by reviewing configuration files before running `make up`.
All six were fixed and documented in postmortem PM-001.

| ID | Root cause | Fix applied |
|---|---|---|
| M1 | `redis-cluster-init` command was a YAML folded scalar; `sh: -a: not found` error; cluster init ran once but was not idempotent | Rewrote as YAML block scalar (`\|`) with list form `[sh, -c, script]`; added `cluster_state:ok` guard so second run exits 0 without re-creating |
| M3 | `log_line_prefix` value contained spaces inside an unquoted YAML string; Docker parsed it as multiple argv entries, breaking Postgres startup | Wrapped value in double quotes in `docker-compose.yml` |
| M4 | `bitnami/kafka:3.7.0` tag had been removed from Docker Hub registry | Migrated to `apache/kafka:3.7.0` — the official Apache image |
| M5 | `apache/kafka` uses `KAFKA_*` env var prefix; compose file used `KAFKA_CFG_*` (Bitnami convention); all Kafka config silently ignored; broker failed with `Missing required configuration 'zookeeper.connect'` | Renamed all `KAFKA_CFG_*` variables to `KAFKA_*`; verified with grep |
| M6 | `redis-node.conf` contained inline comments (`key value # comment`); Redis 7.2.5 parser rejects inline comments | Moved all comments to their own lines |
| M7 | ClickHouse native TCP port 9000 was already bound on the host machine | Remapped host port: `"9000:9000"` → `"19000:9000"` via `sed -i`; HTTP interface `:8123` unaffected |

---

### Phase 4 — Infrastructure verification

**`make up && make health` — run twice, both identical:**

```
=== PostgreSQL ===
  ✓ sales_db   ✓ inventory_db   ✓ orders_db

=== Redis Cluster ===
  cluster_state:ok

=== Kafka ===
  ✓ Kafka broker reachable

=== ClickHouse ===
  Ok.   ✓ ClickHouse HTTP

=== UIs ===
  ✓ Kafka UI (http://localhost:18080)

✓ Health check complete
```

**`make redis-cluster-info` output (confirmed):**

```
cluster_state:ok
cluster_slots_assigned:16384
cluster_slots_ok:16384
cluster_known_nodes:6
cluster_size:3
```

**`CLUSTER NODES` output — confirmed primary/replica topology:**

```
redis-node-1  master  slots 0–5460
redis-node-2  master  slots 5461–10922
redis-node-3  master  slots 10923–16383
redis-node-4  slave   mirrors node-3
redis-node-5  slave   mirrors node-1
redis-node-6  slave   mirrors node-2
```

**`make up` idempotency confirmed:** Second run showed Redis cluster-init skipping
creation (`cluster_state:ok` guard triggered) and all 14 containers remaining healthy.

---

### Phase 5 — Supplementary documentation generated

**Technology reference documents (PLANNED placement: `docs/architecture/`):**

| File | Covers |
|---|---|
| `Business-Flow.md` | Buy Now flow, oversell prevention, technology mapping |
| `Docker.md` | Images, containers, volumes, networks, every container annotated |
| `Redis.md` | Why Redis, race condition, Lua scripts, cluster topology, failure modes |
| `Lua-Scripts.md` | All 5 Lua scripts line-by-line, atomicity, idempotency, lifecycle |
| `KafkaDesign.md` | Topics, partitions, offsets, productId key, Transactional Outbox |
| `ClickHouse.md` | Row vs column storage, 3 query comparisons, schema decisions |

**Flow documents (PLANNED placement: `docs/architecture/`):**

| File | Covers |
|---|---|
| `Buy-Now-Flow.md` | Full 14-step chain with Mermaid sequence diagram |
| `Inventory-Reservation-Flow.md` | 4 Lua script flows with Mermaid sequence diagrams |
| `Kafka-Event-Flow.md` | 4 topic flows, Transactional Outbox detail, DLQ |
| `Analytics-Flow.md` | Kafka → ClickHouse ingestion pipeline |
| `Sale-Lifecycle-Flow.md` | State machine, 4 transition flows with Mermaid diagrams |
| `Saga-Compensation-Flow.md` | 5 saga paths, choreography vs orchestration rationale |

**Lua scripts generated (PLANNED placement: `services/` — service directories not yet created):**

| Script | Planned path |
|---|---|
| `stock_decrement.lua` | `services/inventory-service/src/main/resources/lua/` |
| `stock_prewarm.lua` | `services/inventory-service/src/main/resources/lua/` |
| `stock_release.lua` | `services/inventory-service/src/main/resources/lua/` |
| `stock_reconcile.lua` | `services/inventory-service/src/main/resources/lua/` |
| `rate_limit.lua` | `services/sale-service/src/main/resources/lua/` |

---

### Phase 6 — Context file management

**Documentation audit performed.** Contradictions found between context files:

| ID | Files | Issue |
|---|---|---|
| C-A | `CURRENT_STATE.md` vs `PROJECT_TRUTH.md` | Redis cluster state: `CURRENT_STATE.md` showed `ok`, `PROJECT_TRUTH.md` showed NOT VERIFIED |
| C-B | `CURRENT_STATE.md` vs `PROJECT_TRUTH.md` | Kafka broker: same class of contradiction |
| C-C | `CURRENT_STATE.md` internal | Container count stated as 17; verified count is 14 |
| C-D | `PROJECT_TRUTH.md` | Missing P7, S2, M14 open issues (rejected — open issues belong only in `CURRENT_STATE.md`) |
| C-E | `TASK_TEMPLATE.md` | `CONFLICTS.md` absent from the read list |

**Approved changes applied:**

| File | Change |
|---|---|
| `PROJECT_TRUTH.md` | 9 targeted edits: Redis cluster state and Kafka broker status updated to VERIFIED with 2026-06-17 confirmation date throughout all tables and paragraphs |
| `CURRENT_STATE.md` | Container count corrected: `17` → `14` |
| `TASK_TEMPLATE.md` | `CONFLICTS.md` added to read list |

**10 documentation conflicts logged in `CONFLICTS.md`** — all OPEN, all owned by Tarun:

| ID | Category | Topic |
|---|---|---|
| CONFLICT-001 | Design Decision | Rate limiter key schema: `rate:{userId}:{window_minute}` vs `rate:{userId}:{saleId}` |
| CONFLICT-002 | Documentation Error | Idempotency key schema: `idem:{key}` vs `idem:{userId}:{key}` (PRD contradicts itself) |
| CONFLICT-003 | Documentation Error | Redis memory cap: 4 GB per shard vs 4 GB total cluster |
| CONFLICT-004 | Documentation Error | Kafka UI port: `18080` (README) vs `8080` (Build-Plan) |
| CONFLICT-005 | Documentation Error | OrderService consumer group name: `order-svc-reservation-consumer` vs `order-svc-inventory-consumer` |
| CONFLICT-006 | Documentation Error | Container count: 17 stated vs 14 actual (internal to README) |
| CONFLICT-007 | Documentation Error | `analytics.dlq` topic: present in README and ADR, absent from Final-Spec-Council |
| CONFLICT-008 | Future Implementation | Retry topics (`*.retry`): in KafkaDesign + Build-Plan, absent from ranked docs |
| CONFLICT-009 | Design Decision | Redis layer count: 3-layer (ADR) vs 5-layer (RedisDesign.md) |
| CONFLICT-010 | Future Implementation | Application service ports (8081–8085) not in any ranked document |

**`DOCUMENTATION_GUIDE.md` generated** — defines purpose, update frequency, allowed and
forbidden contents for all 6 context files. Includes ownership matrix, conflict escalation
rule, and promotion rule.

---

### Phase 7 — Architecture self-test (Q&A)

Pre-Week-2 self-assessment. 10 primary questions + 7 additional questions proposed.
Questions attempted: 1–8. Questions 9–17 left incomplete (session interrupted by
documentation audit tasks).

| Q | Question | Attempts | Outcome |
|---|---|---|---|
| 1 | Why Redis before Postgres? | 3 | Passed on 3rd attempt. Key correction: `synchronized` is JVM-scope only; Redis single-thread serialises system-wide. Response must not conflate "fast" with "async." |
| 2 | Why Kafka instead of direct REST calls? | 1 | Passed with corrections. Key correction: synchronous HTTP makes caller's latency hostage to callee; Outbox writes Order+OutboxEvent in one `@Transactional` — not "event written to Kafka" inside the transaction. |
| 3 | Why Lua instead of Java? | 1 | Passed with corrections. Key correction: `synchronized` is JVM-scope, invisible across pods; Redis single-thread is system-wide across all pods; no per-pod boundary to slip through. |
| 4 | Why ClickHouse instead of Postgres for analytics? | 1 | Passed with corrections. Key correction: Postgres reads complete rows off disk even for single-column queries because the row is the physical storage unit. ClickHouse reads only the queried column's file. |
| 5 | Why three PostgreSQL databases? | 1 | Passed with corrections. Key correction: InventoryService never writes to `orders_db`. Isolation is an infrastructure decision independent of Kafka; Kafka makes recovery graceful, it does not justify the isolation. |
| 6 | Why Outbox pattern? | 1 | Strongest answer of session. Key addition: `FOR UPDATE SKIP LOCKED` prevents two outbox poller pods from claiming the same rows simultaneously. |
| 7 | Why Redis Cluster instead of single instance? | 2 | Passed on 2nd attempt. Key correction: masters do not sync from Postgres; replicas mirror masters only; Lettuce client library handles failover routing, not InventoryService code; `stock_prewarm.lua` has no relation to failover. |
| 8 | What happens if Kafka goes down? | 1 | Passed with corrections. Key correction: InventoryService has no outbox — `StockReserved` can be lost during sustained Kafka outage; OrderService is protected by outbox; consumers resume from last committed offset on recovery. |
| 9–17 | (9 questions) | 0 | Not attempted — deferred to next session |

**Questions 9–17 (carry forward to next session):**

| Q | Question |
|---|---|
| 9 | What happens if Redis crashes? |
| 10 | How is overselling prevented? |
| 11 | Why is `inventory-events` partitioned by `productId` not `saleId`? |
| 12 | How do you prevent a duplicate order when a client retries? |
| 13 | What is the thundering herd problem and how is it solved? |
| 14 | Why choreography instead of orchestration for the saga? |
| 15 | Why Java 21 virtual threads instead of Spring WebFlux? |
| 16 | Why do Redis keys use hash tags like `{saleId}`? |
| 17 | What happens when stock hits exactly zero? |

---

### Open issues carried into Week 2

| ID | Description | Priority | Target |
|---|---|---|---|
| P7 | `lua-time-limit 5000ms` too high in `redis-node.conf` | Medium | Before Week 3 |
| S2 | All ports bind `0.0.0.0` — should be `127.0.0.1` | Low | Week 2 cleanup |
| M14 | `sleep 5` in Makefile too short for cold starts | Low | Week 2 cleanup |

**Pre-Week-2 blockers (both resolved this session):**

| Blocker | Resolution |
|---|---|
| Redis cluster state NOT VERIFIED | `make redis-cluster-info` confirmed `cluster_state:ok` on 2026-06-17 |
| Kafka broker health NOT VERIFIED | `make health` confirmed `✓ Kafka broker reachable` on 2026-06-17 |

---

### What was left incomplete

1. **Self-test questions 9–17** — not attempted. Carry forward to first Week 2 session.
2. **CONFLICT-010 (application ports)** — 8081–8085 not in any ranked architecture document. Must be resolved before `docker-compose.yml` service entries and `application.yml` files are written in Week 2.
3. **Document placement unconfirmed** — all architecture docs, ADRs, flow docs, and Lua scripts were generated and downloaded but no `git commit` was confirmed. Status of `docs/` directory contents and Lua script placement remains PLANNED.
4. **Postmortem PM-001** — referenced in `CURRENT_STATE.md` as `incidents/postmortems/PM-001-Week01-Infrastructure.md`. File was generated but placement in repository is unconfirmed.

---

### Git state at session end

**Branch:** `main` (single branch — no branching strategy)
**Application code written:** 0 lines
**Java services written:** 0 of 5