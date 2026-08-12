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

---

## SESSION-002
**Date:** 2026-07-03
**Milestone:** Week 2 — SaleService Skeleton
**Outcome:** COMPLETE
**Engineer:** Tarun K Y

---

### Objective

Deliver the SaleService module: `FlashSale` aggregate with sealed-interface state machine,
JPA persistence layer, Spring Boot REST API, and 12 passing tests (8 domain, 4 controller).
`./gradlew :services:sale-service:build` succeeds; `./gradlew :services:sale-service:test` all green.

---

### Phase 1 — Domain model implementation

**Domain layer (zero Spring/JPA dependencies):**
- `domain/vo/` — `SaleId`, `ProductId`, `SaleWindow`, `EndReason` enums, sealed `SaleStatus` interface
- `domain/entity/` — `SaleSchedule` entity with `SaleWindow` and timezone
- `domain/event/` — `SaleScheduled`, `SaleStarted`, `SaleEnded`, `SaleArchived` domain events (defined, not published in Week 2)
- `domain/aggregate/` — `FlashSale` aggregate root with full state machine and event raising
- `domain/exception/` — `SaleCreationException` with `ErrorCode` enum (INVALID_SALE_START, INVALID_STOCK, INVALID_SALE_WINDOW)

**State machine:** `SCHEDULED → ACTIVE → ENDED → ARCHIVED`
- `FlashSale.schedule(...)` — factory method, validates EC-002/003/004 at domain boundary
- `.activate(Instant now)` — SCHEDULED → ACTIVE
- `.end(Instant now, EndReason reason)` — ACTIVE → ENDED
- `.archive(Instant now)` — ENDED → ARCHIVED
- All illegal transitions throw `IllegalStateException`

---

### Phase 2 — Persistence layer

**JPA entities** (separate from domain, in `infra/persistence/`):
- `FlashSaleJpaEntity` — maps to `flash_sales` table with nullable milestone timestamps
- `SaleScheduleJpaEntity` — maps to `sale_schedules` table
- `SaleStatusHistoryJpaEntity` — maps to `sale_status_history` table (insert-only audit log)

**SaleRepository:**
- Translates sealed `SaleStatus` ↔ VARCHAR during save/load
- `save(FlashSale)` — guards against non-SCHEDULED aggregates (insert-only in Week 2)
- `findById(SaleId)` → `Optional<FlashSale>`
- `appendStatusHistory(...)` — immutable audit trail per state transition

**Spring Data repositories** (interfaces):
- `SpringDataFlashSaleRepository` extends `JpaRepository<FlashSaleJpaEntity, UUID>`
- `SpringDataSaleScheduleRepository` with `findBySaleId(UUID)`
- `SpringDataSaleStatusHistoryRepository`

---

### Phase 3 — Application services

**`SaleCommandService`:**
- `createSale(CreateSaleCommand)` — invokes `FlashSale.schedule(...)`, persists via `SaleRepository.save()`, appends initial status history

**`SaleQueryService`:**
- `getById(SaleId)` — retrieves sale via repository, throws `SaleNotFoundException` if missing

**Supporting types:**
- `CreateSaleCommand` — application-layer DTO (distinct from `CreateSaleRequest`)
- `SaleNotFoundException` — mapped to 404 by `GlobalExceptionHandler`

---

### Phase 4 — REST API

**SaleController** (`/api/v1/sales`):
- `POST /api/v1/sales` (CreateSaleRequest) → 201 SaleResponse with saleId
- `GET /api/v1/sales/{id}` → 200 SaleResponse or 404 SaleNotFoundException

**GlobalExceptionHandler:**
- `SaleCreationException` → 400 with EC-002/003/004 error codes
- `MethodArgumentNotValidException` → 400 VALIDATION_ERROR
- `SaleNotFoundException` → 404 SALE_NOT_FOUND
- `IllegalStateException` → 409 ILLEGAL_STATE_TRANSITION

**DTOs:**
- `CreateSaleRequest` — name, productId, totalStock, saleStart, saleEnd, timezone (all required except timezone)
- `SaleResponse` — saleId, name, productId, totalStock, status, saleStart, saleEnd, timezone, version
- `ErrorResponse` — error code, message

---

### Phase 5 — Configuration

**`application.yml`:**
```yaml
server.port: 8081
spring.threads.virtual.enabled: true
spring.datasource: jdbc:postgresql://localhost:5432/sales_db (user: flashsale, password: flashsale_dev)
spring.jpa.hibernate.ddl-auto: validate
spring.flyway.enabled: true
spring.flyway.locations: classpath:db/migration
```

**`ClockConfig`:**
- Provides injectable `Clock` bean for testable time (used by `SaleCommandService`)

---

### Phase 6 — Database migration

**Flyway `V1__init.sql`:**
- `flash_sales` table with status CHECK constraint, indexed on (status, created_at) for dashboard queries
- `sale_schedules` table with unique sale_id FK and indexed start/end for scheduler
- `sale_status_history` table with insert-only audit log, indexed on (sale_id, transitioned_at DESC)
- `set_updated_at()` trigger function for automatic timestamp updates

---

### Phase 7 — Tests

**12 total (all passing):**

**FlashSaleStateMachineTest (10 tests):**
- V1: SCHEDULED → ACTIVE via activate()
- V2: ACTIVE → ENDED via end() — TIME_ELAPSED
- V3: ACTIVE → ENDED via end() — ADMIN_FORCE
- V4: ENDED → ARCHIVED via archive()
- I1: ACTIVE → ACTIVE throws IllegalStateException
- I2: ENDED → ACTIVE throws IllegalStateException
- I3: SCHEDULED → ENDED throws IllegalStateException (skips ACTIVE)
- I4: ACTIVE → ARCHIVED throws IllegalStateException (skips ENDED)
- EC-003: totalStock ≤ 0 throws SaleCreationException.INVALID_STOCK
- EC-002: saleStart not in future throws SaleCreationException.INVALID_SALE_START
- (supplementary: EC-004, event raising)

**SaleControllerTest (4 tests):**
- createSale_returns201WithSaleId
- createSale_missingName_returns400ValidationError
- getSale_found_returns200
- getSale_notFound_returns404

---

### Phase 8 — Build and Gradle setup

**Gradle multi-module structure:**
- Root `settings.gradle` includes `services:sale-service`
- Root `build.gradle` applies Java 21 toolchain globally
- `services/sale-service/build.gradle` with Spring Boot 3.3.4, dependency-management 1.1.6, all required dependencies

**Gradle wrapper:**
- `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.10)
- `gradlew` and `gradlew.bat` executable scripts
- `gradle/wrapper/gradle-wrapper.jar` (bootstrap)

**Build verification:**
```
./gradlew :services:sale-service:build
BUILD SUCCESSFUL in 32s (8 actionable tasks: 7 executed, 1 up-to-date)

./gradlew :services:sale-service:test --rerun-tasks
BUILD SUCCESSFUL in 12s (4 executed)
12 tests passed
```

---

### Deliverables summary

**38 files:**
- 10 domain layer (VOs, aggregate, entity, events, exception)
- 4 application layer (command service, query service, DTOs, exception)
- 7 persistence layer (JPA entities, repositories, mapping)
- 3 configuration layer (Clock bean, app config)
- 2 API layer (controller, exception handler)
- 3 API DTOs
- 2 tests
- 1 Flyway migration
- 1 application.yml
- 4 Gradle configuration files

**All tests passing, build succeeds, Gradle wrapper operational.**

---

### Open issues carried forward

| ID | Description | Priority | Target |
|---|---|---|---|
| P7 | `lua-time-limit 5000ms` too high in redis-node.conf | Medium | Before Week 3 |
| S2 | All ports bind `0.0.0.0` — should be `127.0.0.1` | Low | Week 3 cleanup |
| M14 | `sleep 5` in Makefile too short for cold starts | Low | Week 3 cleanup |

---

### What was left incomplete

1. **Domain events not published** — `SaleScheduled`, `SaleStarted`, `SaleEnded`, `SaleArchived` are raised and held by the aggregate, but Kafka producer wiring is Week 6 scope per Build-Plan.
2. **No state-transition endpoints** — `PATCH /api/v1/sales/{id}/status` (admin force-end), `GET /api/v1/sales/{id}/history` not implemented (Week 3+ scope).
3. **No scheduler wiring** — `activate()` and `end()` exist on the aggregate and pass all tests, but nothing calls them at production time (FR-003/004 scope, later weeks).
4. **No Redis cache** — `GET /api/v1/sales/{id}/active` hot-path not implemented (Week 7 scope).

---

### Self-test questions 9–17

All 17 questions from Week 1 remain intact (9 unattempted). Session focused on implementation
delivery rather than knowledge review. Carry forward to next session.

---

### Git state at session end

**Branch:** `main`
**Last commit:** Week 2: SaleService skeleton — FlashSale aggregate, 12 tests passing, Gradle 8.10 wrapper
**Application code written:** 2,400+ lines (38 files, domain → application → infra → API)
**Java services completed:** 1 of 5 (SaleService)

---

## SESSION-003
**Date:** 2026-07-23 to 2026-07-24
**Milestone:** Week 3 — InventoryService through StockCounterService
**Outcome:** APPROVED SLICES COMPLETE; WEEK 3 STILL IN PROGRESS
**Engineer:** Tarun K Y
**Branch:** `main`
**Starting commit:** `a2e971c` (`week2-complete`)
**Ending implementation commit:** `2a22457` (`Week3: Complete StockCounterService`)

---

### Objective and governing scope

Implement InventoryService incrementally, stopping after every slice for review. The
approved Week 3 implementation scope for the overall milestone is:

1. InventoryService module
2. Product aggregate
3. StockLevel entity
4. StockCount value object
5. Flyway migration
6. Redis atomic decrement
7. Redis pre-warm
8. PostgreSQL fallback
9. Repository locking
10. Correctness tests

The following remained explicitly outside scope throughout this session:

- Kafka integration
- Inventory GET endpoint or any REST endpoint
- Reservation aggregate or other Week 4 work
- Release functionality
- Reconciliation functionality
- Independent StockLevel writes outside Product
- Unrelated SaleService changes or refactoring

Implementation slices were completed and approved one at a time. No work from a later
slice was intentionally pulled into an earlier one.

---

### Slice 1 — InventoryService module skeleton

**Status:** COMPLETE and approved
**Commit:** `0444c9b` (`feat: bootstrap InventoryService module`)

**Files:**

- `settings.gradle`
  - Added `include 'services:inventory-service'`.
  - This was the only existing repository file modified in this slice.
- `services/inventory-service/build.gradle`
  - Spring Boot `3.3.4`
  - Spring dependency-management `1.1.6`
  - `spring-boot-starter-web` (application/actuator runtime only; no endpoints added)
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-data-redis`
  - `spring-boot-starter-actuator`
  - Flyway core and PostgreSQL database support
  - PostgreSQL runtime driver
  - Spring Boot test starter
- `InventoryServiceApplication`
  - Spring Boot entry point in package `com.flashsale.inventory`.
- `application.yml`
  - Port `${INVENTORY_SERVICE_PORT:8082}`
  - Application name `inventory-service`
  - Java virtual threads enabled with `spring.threads.virtual.enabled=true`
  - PostgreSQL URL `${INVENTORY_SERVICE_DB_URL:jdbc:postgresql://localhost:5433/inventory_db}`
  - JPA `ddl-auto: validate`, `open-in-view: false`
  - Flyway enabled at `classpath:db/migration`
  - Redis Cluster nodes configured from `SPRING_DATA_REDIS_CLUSTER_NODES`
  - Actuator `health` and `info`

**Java 21 decision:** InventoryService inherits the root `subprojects` Java 21 toolchain.
The toolchain was not duplicated inside the module build file. Gradle resolved both
`sourceCompatibility` and `targetCompatibility` to `21`.

**Intentionally absent:** domain code, persistence code, migration, API, Kafka, and
business logic.

---

### Slice 2 — Framework-free Inventory domain model

**Status:** COMPLETE and approved
**Commit:** `213570a` (`feat(inventory): implement inventory domain model`)
**Tests after slice:** 27 passed, 0 failed, 0 skipped

#### New production classes

| Class | Package | Responsibility |
|---|---|---|
| `Product` | `com.flashsale.inventory.domain.aggregate` | Aggregate root owning every StockLevel for one Product; allocates stock, enforces ownership/uniqueness/allocation ceiling, exposes immutable snapshots, and tracks aggregate version. |
| `StockLevel` | `com.flashsale.inventory.domain.entity` | Identified entity representing one Product allocation for one Sale; carries total allocation, current stock, and entity version. |
| `StockCount` | `com.flashsale.inventory.domain.vo` | Immutable non-negative `int` stock value; provides availability, sold-out, positive increment/decrement, and `canDecrement`. |
| `ProductId` | `com.flashsale.inventory.domain.vo` | Typed UUID identity for Product with generation and parsing factories. |
| `SaleId` | `com.flashsale.inventory.domain.vo` | Typed opaque UUID reference to SaleService; Inventory does not import SaleService types. |
| `StockLevelId` | `com.flashsale.inventory.domain.vo` | Typed UUID identity for StockLevel. |

#### New test classes

| Test class | Responsibility | Tests |
|---|---|---:|
| `ProductTest` | Product creation, allocations, ceiling, duplicate Sale, ownership, immutable snapshots, and safe reconstitution. | 10 |
| `StockLevelTest` | Initial allocation, reconstitution, zero/current/ceiling/version boundaries. | 6 |
| `StockCountTest` | Negative rejection, availability, immutable arithmetic, positive quantities, underflow/overflow behavior. | 7 |
| `TypedIdTest` | UUID/string creation, null rejection, type distinction, and generated-ID uniqueness. | 4 |

#### Domain decisions

- Domain packages contain no Spring, JPA, Hibernate, Redis, or Kafka imports/annotations.
- `Product.totalStock` may be zero but never negative.
- A StockLevel allocation must be strictly positive.
- The sum of `StockLevel.totalAllocated` values may never exceed Product total stock.
- Product stores StockLevels keyed by SaleId, enforcing one StockLevel per Product + Sale.
- Product rejects a reconstituted StockLevel whose ProductId differs from the aggregate ID.
- `StockLevel.currentStock` must be between zero and `totalAllocated`, inclusive.
- Product and StockLevel versions may never be negative.
- Product collection access returns immutable snapshots.
- Failed allocations do not change the aggregate or its version.
- `StockLevelId` is UUID-backed. The one-Product-plus-one-Sale rule is a separate aggregate
  and database uniqueness invariant, not the entity's ID representation.
- Release and reconciliation commands were not added.

---

### Slice 3 — Inventory persistence layer

**Status:** COMPLETE and approved
**Committed as part of:** `eecc75c` (`Week3: Complete Redis adapter layer`)
**Tests after slice:** 33 passed, 0 failed, 0 skipped

#### New production classes

| Class | Package | Responsibility |
|---|---|---|
| `ProductJpaEntity` | `com.flashsale.inventory.infra.persistence` | JPA representation of Product: `id`, `total_stock`, `version`, and owned StockLevel collection. Uses `@Version`, `@OneToMany`, cascade-all, lazy loading, and orphan removal. |
| `StockLevelJpaEntity` | `com.flashsale.inventory.infra.persistence` | JPA representation of StockLevel: `id`, Product owner, `sale_id`, `total_allocated`, `current_stock`, and `version`. Uses `@Version` and a unique Product + Sale constraint. |
| `ProductPersistenceMapper` | `com.flashsale.inventory.infra.persistence` | The sole domain/JPA translation boundary. Maps the complete Product aggregate tree in both directions, establishes both sides of ownership, preserves versions, and lets domain reconstitution reject invalid persisted state. |
| `SpringDataProductRepository` | `com.flashsale.inventory.infra.persistence` | `JpaRepository<ProductJpaEntity, UUID>`; overrides `findById` with an entity graph for `stockLevels`. |
| `ProductRepository` | `com.flashsale.inventory.infra.persistence` | Aggregate-oriented JPA adapter supporting `findById(ProductId)` and `save(Product)`. Persists StockLevels only through Product and uses `saveAndFlush` before mapping the returned aggregate. |

#### New test classes

| Test class | Responsibility | Tests |
|---|---|---:|
| `ProductPersistenceMapperTest` | Complete domain-to-JPA and JPA-to-domain mapping, ownership, version preservation, and rejection of invalid persisted stock. | 3 |
| `ProductRepositoryTest` | Loading, missing Product behavior, mapping, save-and-flush delegation, and returned aggregate. | 3 |

#### Persistence decisions

- Domain classes remained unchanged and unannotated.
- Persistence entities are separate mutable JPA data holders.
- There is intentionally no independent Spring Data StockLevel repository. Such a
  repository would allow writes around the Product aggregate boundary.
- Product owns StockLevels with `cascade = ALL` and `orphanRemoval = true`.
- The child owns the FK through `@ManyToOne` / `@JoinColumn(product_id)`.
- An entity graph loads Product + StockLevels for mapping without relying on an open session.
- Both Product and StockLevel persistence entities use optimistic `@Version` fields.
- Mapper methods round-trip domain versions exactly.

---

### Slice 4 — Initial Inventory V1 Flyway migration

**Status:** COMPLETE and approved
**Committed as part of:** `eecc75c`
**Tests after slice:** 33 passed, 0 failed, 0 skipped

**New file:** `services/inventory-service/src/main/resources/db/migration/V1__init.sql`

#### `products`

Exact columns matching `ProductJpaEntity`:

| Column | SQL type | Rules |
|---|---|---|
| `id` | `UUID` | NOT NULL, primary key, application-assigned |
| `total_stock` | `INTEGER` | NOT NULL, `>= 0` |
| `version` | `BIGINT` | NOT NULL, `>= 0`, optimistic lock |

Constraints:

- `products_pkey`
- `products_total_stock_ck`
- `products_version_ck`

#### `stock_levels`

Exact columns matching `StockLevelJpaEntity`:

| Column | SQL type | Rules |
|---|---|---|
| `id` | `UUID` | NOT NULL, primary key, application-assigned |
| `product_id` | `UUID` | NOT NULL, FK to `products(id)` |
| `sale_id` | `UUID` | NOT NULL, opaque cross-service reference |
| `total_allocated` | `INTEGER` | NOT NULL, `> 0` |
| `current_stock` | `INTEGER` | NOT NULL, `>= 0`, `<= total_allocated` |
| `version` | `BIGINT` | NOT NULL, `>= 0`, optimistic lock |

Constraints:

- `stock_levels_pkey`
- `stock_levels_product_id_fk` with `ON DELETE RESTRICT`
- `stock_levels_product_sale_unique`
- `stock_levels_total_allocated_ck`
- `stock_levels_current_stock_ck`
- `stock_levels_stock_ceiling_ck`
- `stock_levels_version_ck`

Index decisions:

- PostgreSQL automatically creates the Product and StockLevel PK indexes.
- The Product + Sale UNIQUE constraint creates a B-tree index beginning with
  `product_id`; this supports both uniqueness and the Product-to-StockLevel join.
- No redundant standalone `product_id` index was added.
- No FK exists for `sale_id` because SaleService owns another database.
- No database UUID defaults exist because IDs are generated by the domain.

Explicitly absent: Reservation, release, reconciliation, outbox/Kafka, audit/log,
Redis, created/updated timestamp, and Week 4 tables or columns.

---

### Slice 5 — Approved Redis Lua decrement integration

**Status:** COMPLETE and approved
**Committed as part of:** `eecc75c`
**Tests after slice:** 39 passed, 0 failed, 0 skipped

**Approved existing resource used unchanged:**
`services/inventory-service/src/main/resources/lua/stock-decrement.lua`

Script contract:

- `KEYS[1] = stock:{saleId}`
- `ARGV[1] = quantity`
- `-2` = cache miss
- `-1` = sold out
- non-negative = remaining stock
- Performs atomic `DECRBY` only after its checks.

Approved resource SHA-1:
`2dab8322003da880c4aa8a4f55ca9aefaadc0215`

#### New production classes

| Class | Package | Responsibility |
|---|---|---|
| `RedisScriptConfiguration` | `com.flashsale.inventory.infra.config` | Loads `lua/stock-decrement.lua` as a singleton `DefaultRedisScript<Long>` using `ClassPathResource`; Spring can reuse the computed SHA for script execution. |
| `StockDecrementLuaExecutor` | `com.flashsale.inventory.infra.redis` | Formats `stock:{saleId}`, serializes quantity, calls `StringRedisTemplate.execute`, and returns the raw nullable Long without interpreting it. |

#### New test classes

| Test class | Responsibility | Tests |
|---|---|---:|
| `RedisScriptConfigurationTest` | Classpath loading, approved content markers, Long result type, exact SHA, singleton bean, and SHA reuse. | 1 |
| `StockDecrementLuaExecutorTest` | Hash-tagged key, quantity argument, RedisTemplate delegation, raw result preservation for `-2`, `-1`, `0`, and positive results, and null SaleId guard. | 5 |

No live Redis call occurred in this slice; execution wiring was unit-tested with mocks.

---

### Slice 6 — Redis adapter and application port

**Status:** COMPLETE and approved
**Commit:** `eecc75c`
**Tests after slice:** 44 passed, 0 failed, 0 skipped

#### New production classes

| Class | Package | Responsibility |
|---|---|---|
| `StockDecrementPort` | `com.flashsale.inventory.application.port` | Redis-neutral outbound interface: `decrement(SaleId, int) -> Long`. Exposes no Spring, Redis, Lua, key, or serialization types. |
| `RedisStockDecrementAdapter` | `com.flashsale.inventory.infra.redis` | Implements StockDecrementPort by delegating unchanged to StockDecrementLuaExecutor. Contains no branching or result interpretation. |

#### New test class

| Test class | Responsibility | Tests |
|---|---|---:|
| `RedisStockDecrementAdapterTest` | Exact SaleId/quantity delegation and unchanged propagation of `-2`, `-1`, `0`, positive, and null executor values. | 5 |

The Lua executor was reused without modification.

---

### Slice 7 — StockCounterService

**Status:** COMPLETE and approved
**Commit:** `2a22457` (`Week3: Complete StockCounterService`)
**Tests after slice:** 55 passed, 0 failed, 0 skipped

#### New production classes

| Class | Package | Responsibility |
|---|---|---|
| `ProductRepository` | `com.flashsale.inventory.application.port` | Application-facing aggregate repository interface with `findById(ProductId)` and `save(Product)`. |
| `StockCounterService` | `com.flashsale.inventory.application` | Loads Product through the port, resolves the owned StockLevel, validates request bounds through `StockCount`, invokes StockDecrementPort exactly once, and maps approved raw codes to application outcomes. |
| `StockDecrementResult` | `com.flashsale.inventory.application` | Sealed result: `Decremented(StockCount)`, `SoldOut`, or `CacheMiss`. |

#### Existing file changed

`com.flashsale.inventory.infra.persistence.ProductRepository` now implements the
application `ProductRepository` interface. Only the implemented-interface declaration
and `@Override` markers changed in this slice. JPA mappings, queries, transaction
annotations, and mapper behavior did not change.

#### New test class

| Test class | Responsibility | Tests |
|---|---|---:|
| `StockCounterServiceTest` | Success, sold out, cache miss, Product/StockLevel absence, quantity bounds, null/unknown/out-of-range port values, no repository save, and exactly one Redis-port invocation. | 11 |

#### Current orchestration

1. Require non-null ProductId and SaleId.
2. Load Product via the application `ProductRepository`.
3. Resolve the Sale's StockLevel through `Product.stockLevelFor`; never query or write a
   StockLevel independently.
4. Use `stockLevel.totalAllocated().canDecrement(quantity)` to enforce a positive request
   no larger than the sale allocation.
5. Invoke `StockDecrementPort.decrement(saleId, quantity)` exactly once.
6. Map raw `-2` to `CacheMiss`; do not retry, fall back, save, or re-warm.
7. Map raw `-1` to `SoldOut`.
8. Map a non-negative in-range value to `Decremented(StockCount)`.
9. Reject null, unknown negative, or values above Java/domain `int` range.

The application package imports application ports and domain types only; it has no
dependency on infrastructure, Redis, Lua, or JPA classes.

---

### Packages introduced in this session

Production:

- `com.flashsale.inventory`
- `com.flashsale.inventory.application`
- `com.flashsale.inventory.application.port`
- `com.flashsale.inventory.domain.aggregate`
- `com.flashsale.inventory.domain.entity`
- `com.flashsale.inventory.domain.vo`
- `com.flashsale.inventory.infra.config`
- `com.flashsale.inventory.infra.persistence`
- `com.flashsale.inventory.infra.redis`

Tests mirror:

- `com.flashsale.inventory.application`
- `com.flashsale.inventory.domain.aggregate`
- `com.flashsale.inventory.domain.entity`
- `com.flashsale.inventory.domain.vo`
- `com.flashsale.inventory.infra.config`
- `com.flashsale.inventory.infra.persistence`
- `com.flashsale.inventory.infra.redis`

Resources:

- `services/inventory-service/src/main/resources/db/migration`
- Existing `services/inventory-service/src/main/resources/lua` retained.

Current InventoryService source inventory:

- 19 production Java classes/interfaces
- 10 Java test classes
- 6 resource files (`application.yml`, V1 migration, and four pre-existing Lua scripts)

---

### Architecture decisions established by repository reality

1. **Java 21 / Spring Boot 3.3.4:** Java toolchain remains root-owned; virtual threads
   are enabled per service.
2. **Framework-free domain:** No framework annotations or dependencies may enter
   `com.flashsale.inventory.domain`.
3. **Aggregate boundary:** Product owns StockLevel. All allocation access is through Product.
4. **No child repository:** StockLevel has no standalone repository.
5. **Typed IDs:** Raw UUIDs do not cross domain method boundaries where a typed ID exists.
6. **StockLevel identity:** UUID-backed StockLevelId plus a separate Product + Sale
   uniqueness invariant.
7. **Database-per-service:** SaleId is opaque in inventory_db and has no database FK.
8. **Separate persistence model:** JPA entities remain under `infra.persistence`; mapping is
   isolated in ProductPersistenceMapper.
9. **Aggregate fetch:** Spring Data uses an entity graph to load StockLevels with Product.
10. **Optimistic locking:** Product and StockLevel JPA entities use `@Version`; corresponding
    SQL columns are non-null BIGINTs.
11. **Minimal V1 schema:** The migration follows approved Java entities, not stale design
    documents containing name/SKU/price/audit/Redis/reconciliation columns.
12. **Application-assigned UUIDs:** No database UUID defaults.
13. **Index economy:** PK and UNIQUE-created indexes are reused; no redundant
    `stock_levels(product_id)` index.
14. **Lua ownership:** The approved decrement script is loaded, not copied into Java.
15. **SHA reuse:** `DefaultRedisScript<Long>` is a singleton bean with stable SHA.
16. **Redis Cluster key:** Decrement uses `stock:{saleId}` so the sale ID is a hash tag.
17. **Layered Redis boundary:** Lua executor handles Redis mechanics; Redis adapter implements
    a Redis-neutral application port; StockCounterService interprets outcomes.
18. **Raw adapter contract:** Redis adapter and executor do not interpret `-2`/`-1`/success.
19. **Cache miss behavior today:** StockCounterService exposes `CacheMiss`; it does not retry
    or invoke PostgreSQL.
20. **No persistence update after Redis today:** A successful Redis decrement does not call
    ProductRepository.save in the current slice.
21. **No unrelated changes:** SaleService was not modified.

---

### Invariants that must never change without an explicit architecture decision

#### Domain

- StockCount is never negative.
- Allocation quantity is strictly positive.
- Sum of Product allocations never exceeds Product total stock.
- At most one StockLevel exists per Product + Sale.
- Every StockLevel inside Product has the same ProductId as its owner.
- StockLevel current stock is `0 <= currentStock <= totalAllocated`.
- Product and StockLevel versions are never negative.
- Product state cannot be mutated by editing a returned collection.
- Invalid allocation attempts are atomic: no child added and no version increment.
- InventoryContext treats SaleId as opaque and imports no SaleService classes.

#### Persistence/database

- Domain classes remain free of JPA annotations.
- Product and StockLevel persistence entities remain separate from domain classes.
- StockLevels are persisted only via Product.
- `stock_levels.product_id` is mandatory and FK-constrained.
- `(product_id, sale_id)` remains unique in both JPA metadata and SQL.
- `current_stock` never exceeds `total_allocated` in SQL.
- Product/StockLevel optimistic version columns remain mapped and non-negative.
- No cross-database Sale FK is introduced.

#### Redis/application

- Redis decrement is atomic through the approved Lua script; never replace it with a
  client-side GET-then-DECR sequence.
- The stock key remains `stock:{saleId}`.
- Script return contract remains exactly `-2`, `-1`, or non-negative.
- Lua executor and Redis adapter return raw values without business interpretation.
- StockCounterService accesses allocation through Product before invoking the port.
- Cache miss currently causes one `CacheMiss` result and zero fallback/retry/re-warm calls.
- Unknown negative, null, and out-of-int-range port values are rejected.
- No successful Redis decrement currently triggers a Product save.

---

### Verification commands actually executed

Discovery-only `sed`, `find`, and broad documentation searches are omitted here; every
command used to validate implementation/build behavior is recorded below.

#### Skeleton verification

```bash
git status --short
git branch --show-current
./gradlew projects
```

The first `./gradlew projects` attempt failed in the managed sandbox because Gradle could
not create its cached wrapper `.lck` file under the user Gradle directory. The identical
verification was rerun with approved Gradle cache access:

```bash
./gradlew projects && ./gradlew :services:inventory-service:build
```

Results:

- `projects`: InventoryService and SaleService both recognized; successful in 18s.
- Initial InventoryService skeleton build: successful in 1m 5s; 5 tasks executed;
  tests correctly reported `NO-SOURCE`.

Additional skeleton checks:

```bash
git diff --check
git status --short --untracked-files=all
./gradlew :services:inventory-service:properties | rg '^(sourceCompatibility|targetCompatibility):'
jar tf services/inventory-service/build/libs/inventory-service-0.1.0-SNAPSHOT.jar \
  | rg 'InventoryServiceApplication|application.yml|BOOT-INF/lib/(spring-boot|spring-data-redis|flyway|postgresql)'
git diff --no-index --check /dev/null services/inventory-service/build.gradle
git diff --no-index --check /dev/null \
  services/inventory-service/src/main/java/com/flashsale/inventory/InventoryServiceApplication.java
git diff --no-index --check /dev/null \
  services/inventory-service/src/main/resources/application.yml
```

Confirmed Java source/target 21 and packaged Boot entry point, YAML, Redis, Flyway, and
PostgreSQL libraries.

#### Domain verification

```bash
./gradlew :services:inventory-service:test --tests 'com.flashsale.inventory.domain.*'
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
./gradlew :services:inventory-service:cleanTest :services:inventory-service:test
rg -n '<testsuite ' services/inventory-service/build/test-results/test/*.xml
rg -n 'org\.springframework|jakarta\.persistence|javax\.persistence|org\.hibernate|redis|kafka' \
  services/inventory-service/src/main/java/com/flashsale/inventory/domain \
  services/inventory-service/src/test/java/com/flashsale/inventory/domain
rg -n '[[:blank:]]+$' \
  services/inventory-service/src/main/java/com/flashsale/inventory/domain \
  services/inventory-service/src/test/java/com/flashsale/inventory/domain
```

Results:

- Focused domain run: successful in 17s.
- Full domain-slice build: successful in 11s.
- Final domain rerun: successful in 9s.
- 27 tests passed; forbidden framework/infrastructure search returned no matches.

#### Persistence verification

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
rg -n '<testsuite ' services/inventory-service/build/test-results/test/*.xml
git diff --exit-code HEAD -- \
  services/inventory-service/src/main/java/com/flashsale/inventory/domain \
  services/inventory-service/src/test/java/com/flashsale/inventory/domain
rg -n 'redis|flyway|kafka|RestController|Controller|application\.service' \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence \
  services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence
rg -n '[[:blank:]]+$' \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence \
  services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence
rg -n 'jakarta\.persistence|org\.springframework' \
  services/inventory-service/src/main/java/com/flashsale/inventory/domain \
  services/inventory-service/src/test/java/com/flashsale/inventory/domain
```

Result: build successful in 22s; 33 tests passed; domain unchanged; excluded persistence
scope and domain framework scans returned no matches.

#### Migration verification

```bash
rg -n '^CREATE TABLE|^[[:space:]]+[a-z_]+[[:space:]]+(UUID|INTEGER|BIGINT)|CONSTRAINT|PRIMARY KEY|FOREIGN KEY|REFERENCES|UNIQUE|CHECK' \
  services/inventory-service/src/main/resources/db/migration/V1__init.sql
rg -n '@Table|@Id|@Column|@Version|@JoinColumn|@OneToMany|@ManyToOne|UniqueConstraint|private (UUID|int|long|List)' \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductJpaEntity.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/StockLevelJpaEntity.java
rg -ni 'reservation|release|reconcil|kafka|audit|redis|created_at|updated_at|outbox|event' \
  services/inventory-service/src/main/resources/db/migration/V1__init.sql
rg -n '^CREATE TABLE' \
  services/inventory-service/src/main/resources/db/migration/V1__init.sql
./gradlew :services:inventory-service:clean :services:inventory-service:build
jar tf services/inventory-service/build/libs/inventory-service-0.1.0-SNAPSHOT.jar \
  | rg 'BOOT-INF/classes/db/migration/V1__init.sql'
rg -n '[[:blank:]]+$' \
  services/inventory-service/src/main/resources/db/migration/V1__init.sql
```

Result: exactly two tables; one-for-one JPA column/type/nullability/version mapping;
excluded-structure search returned no matches; build successful in 18s with 33 tests;
migration packaged at `BOOT-INF/classes/db/migration/V1__init.sql`.

#### Lua integration verification

```bash
shasum services/inventory-service/src/main/resources/lua/stock-decrement.lua
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
rg -n '<testsuite ' services/inventory-service/build/test-results/test/*.xml
git diff --exit-code -- \
  services/inventory-service/src/main/resources/lua/stock-decrement.lua \
  services/inventory-service/src/main/java/com/flashsale/inventory/domain
rg -n 'StockCounterService|fallback|prewarm|Repository|RestController|Kafka|switch[[:space:]]*\(|result[[:space:]]*[<>=]' \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/config/RedisScriptConfiguration.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/StockDecrementLuaExecutor.java
jar tf services/inventory-service/build/libs/inventory-service-0.1.0-SNAPSHOT.jar \
  | rg 'BOOT-INF/classes/(lua/stock-decrement.lua|com/flashsale/inventory/infra/(config/RedisScriptConfiguration|redis/StockDecrementLuaExecutor)\.class)'
rg -n '[[:blank:]]+$' \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/config/RedisScriptConfiguration.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/StockDecrementLuaExecutor.java \
  services/inventory-service/src/test/java/com/flashsale/inventory/infra/config/RedisScriptConfigurationTest.java \
  services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/StockDecrementLuaExecutorTest.java
```

Result: approved SHA matched; build successful in 18s; 39 tests passed; resource and both
classes packaged; excluded-scope and whitespace scans returned no matches.

#### Redis adapter verification

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
rg -n '<testsuite ' services/inventory-service/build/test-results/test/*.xml
rg -n 'StockCounterService|fallback|re-?warm|prewarm|Repository|RestController|Kafka|switch[[:space:]]*\(|if[[:space:]]*\(|executorResult[[:space:]]*[<>=]' \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockDecrementPort.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapter.java \
  services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapterTest.java
rg -n '^import (org\.springframework|.*redis)' \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockDecrementPort.java
jar tf services/inventory-service/build/libs/inventory-service-0.1.0-SNAPSHOT.jar \
  | rg 'BOOT-INF/classes/com/flashsale/inventory/(application/port/StockDecrementPort|infra/redis/RedisStockDecrementAdapter)\.class'
rg -n '[[:blank:]]+$' \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockDecrementPort.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapter.java \
  services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapterTest.java
```

Result: build successful in 23s; 44 tests passed; port contained no Redis/Spring imports;
no adapter business branching; port and adapter packaged.

#### StockCounterService verification

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
rg -n '<testsuite ' services/inventory-service/build/test-results/test/*.xml
rg -n '^import com\.flashsale\.inventory\.infra|RedisStockDecrementAdapter|StockDecrementLuaExecutor|StringRedisTemplate|RedisScript' \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/StockDecrementResult.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/port/ProductRepository.java \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockDecrementPort.java
rg -n 'save\(|fallback|retry|re-?warm|prewarm|RestController|Kafka' \
  services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java
git diff --exit-code HEAD -- \
  services/inventory-service/src/main/java/com/flashsale/inventory/domain \
  services/inventory-service/src/main/resources/db/migration/V1__init.sql
jar tf services/inventory-service/build/libs/inventory-service-0.1.0-SNAPSHOT.jar \
  | rg 'BOOT-INF/classes/com/flashsale/inventory/application/(StockCounterService|StockDecrementResult|port/ProductRepository)\.class'
rg -n '[[:blank:]]+$' \
  services/inventory-service/src/main/java/com/flashsale/inventory/application \
  services/inventory-service/src/test/java/com/flashsale/inventory/application
```

Result: build successful in 19s; 55 tests passed; application-to-infrastructure import
scan returned no matches; no save/fallback/retry/warm implementation; classes packaged.

Non-failing build warnings observed:

- Gradle reported deprecated features that will be incompatible with Gradle 9.0.
- Test JVM reported class-data-sharing limitation after Mockito appended to the bootstrap
  classpath.

Neither warning caused a test or build failure.

---

### Final test inventory

| Test class | Tests | Result |
|---|---:|---|
| `ProductTest` | 10 | PASS |
| `StockLevelTest` | 6 | PASS |
| `StockCountTest` | 7 | PASS |
| `TypedIdTest` | 4 | PASS |
| `ProductPersistenceMapperTest` | 3 | PASS |
| `ProductRepositoryTest` | 3 | PASS |
| `RedisScriptConfigurationTest` | 1 | PASS |
| `StockDecrementLuaExecutorTest` | 5 | PASS |
| `RedisStockDecrementAdapterTest` | 5 | PASS |
| `StockCounterServiceTest` | 11 | PASS |
| **Total** | **55** | **55 passed, 0 failed, 0 skipped** |

All tests are unit tests. No Testcontainers, live PostgreSQL, live Redis, concurrent
integration, or full Spring Boot application-context test exists yet.

---

### Unresolved assumptions and risks

1. **Optimistic-lock update behavior is not proven against Hibernate/PostgreSQL.**
   Product increments its domain version on allocation. ProductPersistenceMapper writes
   that value into a JPA `@Version` field. Unit tests verify round-trip values, but no real
   detached-update test proves that Hibernate will accept the intended version transition
   rather than treat the incremented detached value as stale. This must be resolved with an
   integration test before optimistic locking is considered production-correct.

2. **ProductRepository load on every decrement conflicts with the stated Redis hot path.**
   The approved StockCounterService contract requires loading Product and resolving its
   StockLevel before calling Redis. This introduces PostgreSQL work on every decrement,
   while architecture documents describe Redis as shielding PostgreSQL from flash-sale
   traffic. Do not silently remove the aggregate load; obtain an explicit architecture
   decision after measuring/clarifying the intended hot path.

3. **PostgreSQL fallback cannot yet mutate StockLevel through the aggregate.**
   StockLevel is immutable and Product currently exposes allocation only. A fallback
   decrement with row locking will need either an explicitly approved Product/StockLevel
   domain command or an explicitly approved repository-level atomic operation. Implementing
   SQL mutation directly today would bypass the aggregate contract.

4. **`PROJECT_TRUTH.md` is stale.**
   The original session decision requires updating it to repository reality, including the
   completed Week 2 skeleton and current Week 3 state. That document was not modified because
   every implementation request limited each slice. It remains an approved documentation task.

5. **Legacy architecture documents disagree with the approved minimal model.**
   DatabaseSchema.md describes Product metadata (`name`, `sku`, price, currency, timestamps)
   and Redis/reconciliation columns that do not exist in the approved domain/JPA/V1 schema.
   The migration intentionally follows repository reality. Do not add legacy columns without
   a new model decision.

6. **Old Week 3 DoD references `stock_reservation_log`.**
   The approved V1 migration explicitly excludes audit/log tables, and no such JPA entity
   exists. PostgreSQL fallback must not invent this table without explicit approval.

7. **No live Lua correctness proof exists.**
   Script loading, SHA, key/argument wiring, and delegation are tested, but the approved
   script has not been executed against real Redis in this session.

8. **Port nullability is asymmetric by design today.**
   StockDecrementPort returns nullable `Long`; executor and adapter pass null through;
   StockCounterService rejects null as an illegal infrastructure result.

9. **Application errors are not API contracts.**
   Missing Product/StockLevel currently throws `NoSuchElementException`; invalid quantity
   throws `IllegalArgumentException`; invalid infrastructure output throws
   `IllegalStateException`. No REST handler or stable external error code exists or is in scope.

10. **Existing pre-warm/release/reconcile Lua files predate this implementation.**
    Only `stock-decrement.lua` is integrated. Presence in resources does not mean the other
    scripts are implemented or approved for use.

---

### Intentionally deferred or excluded

- Redis pre-warm integration and scheduling
- PostgreSQL fallback
- Pessimistic repository locking / `SELECT FOR UPDATE`
- Redis re-warming after a miss or fallback
- Live Redis integration tests
- PostgreSQL/Testcontainers integration tests
- Concurrent oversell/correctness/property tests
- Reservation aggregate, tables, endpoints, expiry, or idempotency (Week 4)
- Release and reconciliation integration
- Kafka producer/consumer/event publication
- Inventory GET endpoint or any REST controller/DTO
- Stock reservation log/audit table
- PROJECT_TRUTH.md update
- SaleService changes

Existing `stock-release.lua` and `stock-reconcile.lua` remain unused and are not part of
remaining Week 3 implementation unless scope is explicitly expanded.

---

### Exact remaining Week 3 tasks

The next engineer must not treat Week 3 as complete. The remaining approved implementation
work, in dependency order, is:

1. **Redis pre-warm slice**
   - Integrate existing `lua/stock-prewarm.lua`.
   - Add script configuration, executor, Redis-neutral port, adapter, and focused unit tests.
   - Use keys `stock:{saleId}` and `stock:warmed:{saleId}` on the same Redis hash slot.
   - Pass total stock and TTL.
   - Preserve the approved return contract: `1 = warmed`, `0 = already warmed`.
   - Define/approve the orchestration input needed to calculate TTL as
     `saleEnd - now + 600 seconds`; Inventory's current Product/StockLevel model has no sale
     end timestamp.
   - Do not implement release or reconciliation while doing this.

2. **Pre-warm trigger/orchestration decision**
   - The old build plan says schedule pre-warm 60 seconds before sale start, but Inventory
     has no SaleWindow and Kafka is excluded.
   - Decide explicitly how Inventory learns SaleId, total stock, sale start/end, and when the
     pre-warm call is triggered. Do not invent Kafka or an endpoint.

3. **PostgreSQL fallback domain contract**
   - Resolve whether Product/StockLevel receives an approved decrement command or whether a
     repository-level atomic mutation is exceptionally allowed.
   - Preserve non-negative stock, Product ownership, and current <= total allocation.
   - Define the result contract for cache miss plus fallback success/sold-out.
   - Do not add `stock_reservation_log` under the current schema approval.

4. **Repository locking**
   - Add the minimal Product-owned StockLevel lookup/update needed for fallback.
   - Use a real transaction and PostgreSQL pessimistic row lock (`SELECT ... FOR UPDATE` /
     Spring `PESSIMISTIC_WRITE`).
   - Keep the Product + Sale uniqueness invariant.
   - Verify optimistic/pessimistic version behavior against real PostgreSQL.
   - Do not expose an unrestricted child repository.

5. **Wire fallback into StockCounterService**
   - On `CacheMiss` or Redis connectivity failure, invoke the approved PostgreSQL fallback.
   - Maintain exactly one authoritative decrement.
   - Do not guess stock.
   - Redis re-warming remains deferred unless separately approved.

6. **Correctness and integration tests**
   - Execute `stock-decrement.lua` against real Redis.
   - Verify return codes `-2`, `-1`, and non-negative across boundary values.
   - Verify stock reaches zero and never becomes negative.
   - Verify quantity decrement and insufficient-stock behavior.
   - Add concurrent decrement coverage proving no oversell.
   - Add PostgreSQL fallback integration coverage with Redis unavailable.
   - Add concurrent row-lock coverage proving serial, non-negative fallback decrements.
   - Add Flyway + Hibernate validation against real inventory_db.
   - Specifically test the unresolved Product/JPA version semantics.

7. **Final Week 3 documentation reconciliation**
   - Update `context/PROJECT_TRUTH.md` to current repository reality.
   - Record which old Build-Plan/DatabaseSchema statements are obsolete.
   - Mark Week 3 complete only after pre-warm, fallback, locking, and correctness tests pass.

Not remaining Week 3 work: Kafka, Inventory GET endpoint, Reservation/Week 4, Release, or
Reconciliation.

---

### Git state at session end

Implementation commits created during this conversation:

| Commit | Description |
|---|---|
| `0444c9b` | InventoryService module skeleton |
| `213570a` | Framework-free Inventory domain model |
| `eecc75c` | Persistence, V1 migration, Lua integration, and Redis adapter layer |
| `2a22457` | ProductRepository application port and StockCounterService |

Before this SESSION_LOG append:

- Branch: `main`
- HEAD: `2a22457`
- `origin/main`: `2a22457`
- Working tree: clean
- InventoryService: 19 production classes/interfaces, 10 test classes, 55 passing tests
- Week 3 status: incomplete; remaining work is listed above

This SESSION_LOG append is the only working-tree modification made after `2a22457`.

---

## SESSION-004
**Date:** 2026-07-24
**Milestone:** Week 3 — PostgreSQL Fallback
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**Implementation commit:** `9bb3ad7` (`feat(inventory): implement PostgreSQL fallback adapter`)

---

### Objective and governing scope

Implement only the approved PostgreSQL fallback slice on top of the completed
StockCounterService work.

Required behavior:

- Preserve every completed Week 3 slice.
- On Redis cache miss or translated Redis connection failure, invoke one
  authoritative PostgreSQL fallback decrement.
- Keep the fallback behind an application-owned port.
- Mutate current stock through the Product aggregate.
- Use a real transaction and PostgreSQL pessimistic locking.
- Preserve Product ownership, Product + Sale uniqueness, non-negative stock,
  version mapping, and the existing domain/JPA translation boundary.
- Return success or sold out from the fallback.

Explicitly excluded:

- Redis re-warming
- Redis pre-warm
- REST/API work
- Kafka
- Reservation/Week 4 work
- Retry logic
- Release or reconciliation
- Schema changes, audit tables, or `stock_reservation_log`
- SaleService changes
- Unrelated refactoring

---

### Product-owned fallback decrement

`Product.decrementStock(SaleId, int)` is the approved domain mutation.

Behavior:

- Rejects null SaleId.
- Rejects non-positive quantity.
- Requires an owned StockLevel for the SaleId.
- Returns `Optional.empty()` when durable current stock is insufficient.
- Leaves Product and StockLevel state unchanged on insufficient stock.
- On success, decrements current stock by exactly the requested quantity.
- Replaces the immutable owned StockLevel with the decremented state.
- Advances the StockLevel domain version.
- Leaves the Product aggregate version unchanged because the mutation is owned
  by the child entity.

The aggregate continues to expose immutable StockLevel snapshots and no
independent StockLevel mutation or repository was added.

---

### Application ports and orchestration

#### `StockFallbackPort`

New infrastructure-neutral application port:

```text
decrement(ProductId, SaleId, int) -> Optional<StockCount>
```

- Present StockCount = successful durable decrement and remaining stock.
- Empty optional = insufficient durable stock / sold out.
- No PostgreSQL, JPA, transaction, or Spring Data type crosses the port.

#### `StockDecrementUnavailableException`

New application-owned exception signaling that the primary stock counter cannot
be reached. This prevents Redis exception types from entering
StockCounterService.

#### `StockCounterService`

Updated orchestration:

1. Preserve Product and owned StockLevel validation before counter access.
2. Invoke `StockDecrementPort` at most once.
3. Preserve Redis `-1` as sold out and non-negative values as success.
4. On Redis `-2` cache miss, invoke `StockFallbackPort` once.
5. On `StockDecrementUnavailableException`, invoke `StockFallbackPort` once.
6. Map a present fallback StockCount to `Decremented`.
7. Map an empty fallback result to `SoldOut`.
8. Reject a null fallback result as an illegal infrastructure result.

No retry, Redis re-warm, pre-warm, or direct ProductRepository save was added to
StockCounterService. A successful Redis decrement still performs no PostgreSQL
write.

---

### Redis connection-failure translation

`RedisStockDecrementAdapter` still preserves raw `-2`, `-1`, non-negative, and
null executor results without interpreting numeric business outcomes.

It now translates only `RedisConnectionFailureException` into
`StockDecrementUnavailableException`. Other failures are not converted into
fallback outcomes.

`StockDecrementLuaExecutor` and `stock-decrement.lua` were unchanged.

---

### PostgreSQL fallback adapter and locking

`PostgresStockFallbackAdapter` implements `StockFallbackPort`.

The complete operation is annotated `@Transactional`:

1. Call `SpringDataProductRepository.findByIdForUpdate(ProductId)`.
2. Acquire `LockModeType.PESSIMISTIC_WRITE` on the Product aggregate root.
3. Load owned StockLevels inside the same transaction.
4. Map the complete managed JPA aggregate to Product.
5. Invoke `Product.decrementStock`.
6. Return empty without update or flush when durable stock is insufficient.
7. Apply successful domain current stock through
   `ProductPersistenceMapper.applyCurrentStock`.
8. Flush the managed StockLevel before returning remaining stock.

Locking the Product root serializes fallback decrements for its owned
StockLevels without introducing an unrestricted child repository.

`ProductPersistenceMapper` remains the sole domain/JPA translation boundary.
The mapper matches Product identity, StockLevel identity, and SaleId before
applying current stock to the managed child. JPA remains responsible for
advancing the persisted optimistic version during flush.

No Flyway migration or database schema change was required.

---

### Files changed by implementation commit `9bb3ad7`

Production:

- `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockDecrementResult.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockDecrementUnavailableException.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockFallbackPort.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/domain/aggregate/Product.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/PostgresStockFallbackAdapter.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductPersistenceMapper.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/SpringDataProductRepository.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/StockLevelJpaEntity.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapter.java`

Tests:

- `services/inventory-service/src/test/java/com/flashsale/inventory/application/StockCounterServiceTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/domain/aggregate/ProductTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/PostgresStockFallbackAdapterTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapterTest.java`

No documentation file was part of implementation commit `9bb3ad7`.

---

### Verification

Final command:

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Final result:

```text
BUILD SUCCESSFUL in 19s
67 tests passed, 0 failed, 0 errors, 0 skipped
```

The first restricted-sandbox attempt could not create the Gradle wrapper cache
lock under the user Gradle directory. The same command was rerun with approved
cache access. After the mapper-boundary adjustment, the clean build was run
again against the final source state and passed.

Test inventory:

| Test class | Tests | Result |
|---|---:|---|
| `ProductTest` | 14 | PASS |
| `StockLevelTest` | 6 | PASS |
| `StockCountTest` | 7 | PASS |
| `TypedIdTest` | 4 | PASS |
| `ProductPersistenceMapperTest` | 3 | PASS |
| `ProductRepositoryTest` | 3 | PASS |
| `RedisScriptConfigurationTest` | 1 | PASS |
| `StockDecrementLuaExecutorTest` | 5 | PASS |
| `RedisStockDecrementAdapterTest` | 6 | PASS |
| `StockCounterServiceTest` | 14 | PASS |
| `PostgresStockFallbackAdapterTest` | 4 | PASS |
| **Total** | **67** | **67 passed** |

All 67 tests are unit tests.

Additional verification:

- `git diff --check` passed before the implementation commit.
- Documentation, SaleService, Inventory resources/migrations, and all Lua
  resources were unchanged by the implementation commit.
- Scope scans found no REST controller, Kafka, retry annotation, Reservation,
  pre-warm, or Redis re-warming implementation.
- Inventory domain framework-import scan returned no matches.
- Inventory application-to-infrastructure import scan returned no matches.

Non-failing warnings:

- Gradle deprecated-feature warning for future Gradle 9.0 compatibility.
- Test JVM class-data-sharing warning after Mockito bootstrap-classpath
  instrumentation.

---

### Verification boundaries and remaining risks

- The new fallback and lock annotations are unit-tested; no live PostgreSQL or
  Testcontainers execution was performed.
- Concurrent Product-root lock serialization and zero-oversell behavior remain
  to be verified against real PostgreSQL.
- Product/StockLevel optimistic-version interaction remains to be verified
  against Hibernate/PostgreSQL.
- Redis Lua execution remains unverified against live Redis.
- No test currently simulates Redis failure after server-side execution.
- Redis re-warming remains intentionally unimplemented.

---

### Git and milestone state

- Branch: `main`
- Implementation HEAD: `9bb3ad7`
- `origin/main`: `9bb3ad7`
- InventoryService: 22 production Java types
- InventoryService tests: 11 classes, 67 passing tests
- PostgreSQL fallback: complete
- Week 3: still in progress

Remaining Week 3 work:

1. Redis re-warming
2. Pre-warm use case and trigger decision
3. Property-based tests
4. Live Redis/PostgreSQL failure and concurrency tests
5. Regression tests
6. Final documentation reconciliation

Kafka, Inventory REST, Reservation/Week 4, retry logic, release, and
reconciliation remain excluded.

---

## SESSION-005
**Date:** 2026-07-25
**Milestone:** Week 3 — Redis Re-warming
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**Implementation commit:** `10069d8` (`feat(inventory): add Redis re-warming after PostgreSQL fallback`)

---

### Objective and governing scope

Implement only safe Redis re-warming after a successful authoritative
PostgreSQL fallback.

Required behavior:

- Re-warm only after a present `StockFallbackPort` result.
- Use the durable remaining `StockCount`; never guess stock.
- Never overwrite a Redis counter restored or decremented by another request.
- Keep re-warming behind an application-owned port and Redis infrastructure
  adapter.
- Preserve the committed PostgreSQL success if Redis remains unavailable.
- Do not retry.

Explicitly excluded:

- Redis pre-warm integration or scheduling
- Domain or persistence changes
- Flyway, SQL, Lua, Gradle, or configuration changes
- REST/API work
- Kafka
- Reservation/Week 4 work
- Release or reconciliation
- SaleService changes
- Unrelated refactoring

---

### Architecture and behavior

#### Application boundary

`StockRewarmPort` is the Redis-neutral outbound contract:

```text
rewarmIfAbsent(SaleId, StockCount) -> void
```

The port exposes only Inventory domain types. Its contract permits creation of a
missing counter and forbids replacement of an existing counter.

`StockRewarmUnavailableException` is the application-owned signal for Redis
connection failure during re-warming. Redis exception types do not cross into
application orchestration.

#### Redis adapter

`RedisStockRewarmAdapter` implements `StockRewarmPort` with
`StringRedisTemplate.opsForValue().setIfAbsent`.

- Key: `stock:{saleId}`.
- Value: the remaining `StockCount` returned by the successful durable fallback.
- The Redis write is atomic.
- An absent key is restored.
- An existing key is left unchanged.
- `RedisConnectionFailureException` is translated to
  `StockRewarmUnavailableException`.
- No Lua script, pre-warm marker, TTL, retry, or additional Redis key was added.

#### StockCounterService orchestration

`StockCounterService` now:

1. Preserves Product-owned validation and invokes `StockDecrementPort` at most
   once.
2. Invokes `StockFallbackPort` once on Redis cache miss or primary-counter
   unavailability.
3. Returns sold out without re-warming when fallback returns empty.
4. Invokes `StockRewarmPort` once when fallback returns durable remaining stock.
5. Returns the durable `Decremented` result when re-warming succeeds, finds an
   existing counter, or is unavailable.

Redis-native success and sold-out paths do not re-warm. The authoritative
PostgreSQL decrement is never retried or reversed because cache restoration
fails.

---

### Files created

Production:

- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockRewarmPort.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockRewarmUnavailableException.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockRewarmAdapter.java`

Tests:

- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockRewarmAdapterTest.java`

### Files modified

Production:

- `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java`

Tests:

- `services/inventory-service/src/test/java/com/flashsale/inventory/application/StockCounterServiceTest.java`

Documentation updated after implementation:

- `context/SESSION_LOG.md`
- `context/CURRENT_STATE.md`
- `HANDOFF.md`

No domain, persistence, Flyway, Lua, Gradle, configuration, SaleService, REST,
Kafka, Reservation, release, or reconciliation file was changed by the
re-warming implementation.

---

### Tests added

Four tests were added:

1. `RedisStockRewarmAdapterTest.restoresMissingCounterFromDurableRemainingStock`
2. `RedisStockRewarmAdapterTest.leavesExistingCounterUnchanged`
3. `RedisStockRewarmAdapterTest.translatesRedisConnectionFailureToPortOwnedUnavailableSignal`
4. `StockCounterServiceTest.returnsDurableSuccessWhenRewarmingIsUnavailable`

Existing StockCounterService tests were extended to verify:

- one re-warm after cache-miss fallback success;
- one re-warm after unavailable-counter fallback success;
- no re-warm after Redis success, Redis sold out, fallback sold out, invalid
  input, invalid Redis output, or invalid fallback output.

---

### Build verification

Required command:

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Final result:

```text
BUILD SUCCESSFUL in 25s
71 tests passed, 0 failed, 0 errors, 0 skipped
```

Final InventoryService inventory:

- 25 production Java types.
- 12 test classes.
- 71 passing unit tests.

Additional verification:

- Focused StockCounterService and RedisStockRewarmAdapter tests passed.
- `git diff --check` passed.
- Domain and application-port framework/infrastructure import scans returned no
  matches.
- Application-to-infrastructure import scans returned no matches.
- Domain, persistence, migrations, Redis Lua resources, Gradle files,
  configuration, and SaleService remained unchanged.
- The executable InventoryService JAR contains `StockRewarmPort`,
  `StockRewarmUnavailableException`, and `RedisStockRewarmAdapter`.

---

### Remaining risks and assumptions

- Redis set-if-absent behavior is unit-tested but has not been executed against
  live Redis.
- Concurrent fallback/re-warm races have not been verified against live Redis
  and PostgreSQL.
- Re-warmed counters have no TTL because Inventory has no approved sale-end
  input. TTL ownership remains part of the separate pre-warm decision.
- Re-warm connection failure preserves durable success but currently emits no
  log or metric.
- Redis failure after possible server-side decrement remains an unresolved
  ambiguous-execution risk.
- Product/StockLevel pessimistic and optimistic version interaction remains
  unverified against live Hibernate/PostgreSQL.

---

### Remaining Week 3 roadmap

1. Approve and implement the pre-warm use case and trigger/source contract.
2. Add property-based stock correctness tests.
3. Add live Redis/PostgreSQL failure, locking, version, and concurrency tests.
4. Add the remaining regression tests, including ambiguous Redis execution.
5. Reconcile `context/PROJECT_TRUTH.md`, `context/REPOSITORY_INDEX.md`, and
   obsolete Build Plan/Database Schema statements.

Week 3 remains in progress. Kafka, Inventory REST, Reservation/Week 4, retry
logic, release, and reconciliation remain excluded.

---

## SESSION-006
**Date:** 2026-08-04
**Milestone:** Week 3 — Durable-Authority Correctness Gate
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**Implementation commit:** `bca1ff1` (`Week 3 implementation`)

---

### Implementation scope

Implemented Revision 2 of
`Inventory-Durable-Authority-Correctness-Implementation-Handoff.md` exactly.
The previously completed Redis re-warming slice at `10069d8` remains part of
the history, but its request-time `SETNX` implementation was replaced by the
approved durable-authority design.

Canonical flow:

```text
Product validation
  -> one Redis Lua attempt
  -> one PostgreSQL durable decrement
  -> commit
  -> revision-fenced Redis synchronization
  -> PostgreSQL-derived Decremented/SoldOut result
```

Explicitly excluded: pre-warm scheduling and sale timing, TTL calculation,
reservation/idempotency/expiry/release/reconciliation, REST, DTOs, Kafka,
events/outbox/retries/DLQ, circuit breakers, performance redesign, SaleService,
schema migration, and Redis Cluster topology testing.

### Architectural decisions

- PostgreSQL `stock_levels.current_stock` is authoritative for every returned
  decrement outcome; Redis is an atomic projection/admission mechanism.
- Every recognized Redis outcome and indeterminate Redis transport failure
  invokes PostgreSQL exactly once. Invalid deterministic results fail closed.
- `StockCounterService` is non-transactional. Redis executes outside the
  PostgreSQL transaction.
- `TransactionalStockDecrement` owns the Product-root `PESSIMISTIC_WRITE`
  transaction, mapping, domain mutation, managed StockLevel update, flush, and
  revision retrieval.
- `PostgresStockDecrementAdapter` calls the separately proxied transactional
  component, so commit completes before the durable port returns and commit
  failures are translated to `DurableStockUnavailableException`.
- PostgreSQL exclusively determines `Decremented` or `SoldOut`; Redis/PostgreSQL
  disagreement emits a warning.
- Redis synchronization runs only after commit. Its failure preserves and
  warns on the committed durable result; there is no retry or compensation.
- StockLevel version is the Redis projection revision. Product version remains
  unchanged by decrement.
- Projection keys are `stock:{saleId}` and `stock:version:{saleId}` with the
  same Redis hash tag.
- Equal/newer revisions apply authoritative stock; strictly older revisions
  are ignored. Missing stock remains missing, and revisionless stock is
  atomically invalidated.
- Applied synchronization preserves the stock key expiry exactly and mirrors
  that absolute expiry to the revision key.
- Product remains the aggregate root, the domain remains framework-free, and
  no StockLevel repository, schema change, or service dependency was added.

### Files created

Governance:

- `Inventory-Durable-Authority-Correctness-Implementation-Handoff.md`

Production:

- `services/inventory-service/src/main/java/com/flashsale/inventory/application/DurableStockDecrementResult.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockProjectionSyncResult.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/DurableStockDecrementPort.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/DurableStockUnavailableException.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockProjectionSyncPort.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockProjectionSyncUnavailableException.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/PostgresStockDecrementAdapter.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/TransactionalStockDecrement.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockProjectionSyncAdapter.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/StockProjectionSyncLuaExecutor.java`
- `services/inventory-service/src/main/resources/lua/stock-projection-sync.lua`

Tests:

- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/PostgresStockDecrementAdapterTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/TransactionalStockDecrementTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockProjectionSyncAdapterTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/StockProjectionSyncLuaExecutorTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/integration/InventoryInfrastructureTestSupport.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/integration/DurableStockDecrementIntegrationTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/integration/RedisPostgresFailoverIntegrationTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/integration/StockProjectionSyncIntegrationTest.java`

### Files modified

- `services/inventory-service/build.gradle`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockDecrementResult.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/config/RedisScriptConfiguration.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductPersistenceMapper.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductRepository.java`
- `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapter.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/application/StockCounterServiceTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/config/RedisScriptConfigurationTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/ProductPersistenceMapperTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/ProductRepositoryTest.java`
- `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapterTest.java`

Superseded legacy files removed:

- `StockFallbackPort`, `StockRewarmPort`, and
  `StockRewarmUnavailableException`
- `PostgresStockFallbackAdapter` and `RedisStockRewarmAdapter`
- `PostgresStockFallbackAdapterTest` and `RedisStockRewarmAdapterTest`

No domain, JPA entity, Spring Data repository, Flyway migration, existing Lua,
application configuration, root Gradle/settings, SaleService, REST, Kafka,
Reservation, release, reconciliation, or deployment file changed.

### Tests added and extended

- Durable adapter/component unit tests cover delegation, locking, transaction
  ownership, post-flush revision retrieval, insufficient-stock non-mutation,
  and database/transaction/commit failure translation.
- StockCounterService unit tests cover all recognized Redis outcomes,
  indeterminate transport failure, PostgreSQL-derived results, disagreement
  warnings, fail-closed invalid Redis results, post-commit synchronization,
  synchronization failure preservation, and no retries.
- Redis unit tests cover connection loss and timeout translation while
  deterministic script/serialization failures remain fail-closed, plus sync
  key construction, result-code mapping, and script bean identity.
- Real PostgreSQL tests cover Flyway/Hibernate validation, successful and
  insufficient version behavior, Product version stability, deferred commit
  failure rollback, and concurrent pessimistic locking without oversell.
- Real Redis/cross-store tests cover Lua success, cache miss, unavailable Redis,
  failure before and after Lua execution, both disagreement directions,
  revision fencing, equal-revision repair, out-of-order sync, persistent and
  expiring TTL preservation, revision-key loss invalidation, and concurrent
  cache misses that remain absent from Redis.

### Build verification

Required Inventory command:

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result:

```text
BUILD SUCCESSFUL in 41s
126 Inventory tests passed, 0 failed, 0 errors, 0 skipped
```

Final whole-project gate:

```bash
./gradlew clean build
```

Result:

```text
BUILD SUCCESSFUL in 1m 6s
InventoryService: 126 passed
SaleService: 16 passed
0 failed, 0 errors, 0 skipped
```

Final Inventory inventory:

- 30 production Java files.
- 17 runnable test classes.
- 105 unit tests and 21 real Testcontainers tests.
- 126 total Inventory tests.

Additional verification:

- All 21 PostgreSQL/Redis Testcontainers tests passed together.
- `git diff --check` passed.
- Domain, ports, and application dependency scans passed; the existing
  application `@Service` stereotype remains the only permitted Spring import.
- Testcontainers is absent from production runtime dependencies.
- Protected/forbidden paths were unchanged.
- No temporary compatibility bridge or legacy fallback/re-warm type remains.
- The executable JAR contains the new contracts, adapters, transactional
  component, executor, and `stock-projection-sync.lua`.
- Test workers set Docker API 1.40 because Spring Boot 3.3.4 manages
  Testcontainers 1.19.8 while the verified Docker 29 daemon requires API 1.40
  or newer.

### Remaining risks and assumptions

- A PostgreSQL transaction and Product-root lock on every potentially
  successful request increase latency and contention.
- Product-root locking serializes different sales for the same Product.
- Missing or revisionless Redis stock stays missing until a future pre-warm;
  independent revision-key loss can increase PostgreSQL load.
- Future pre-warm/reconciliation must create compatible revision keys.
- Standalone Redis Testcontainers coverage does not prove Redis Cluster
  topology behavior.
- A committed response lost before the client receives it can be retried and
  decrement again; cross-request idempotency remains out of scope.
- StockCounterService still performs the approved Product validation read
  before Redis.
- The SaleService migration defect remains outside this slice.
- `PROJECT_TRUTH.md`, `REPOSITORY_INDEX.md`, and legacy planning/schema
  statements remain stale.

### Remaining Week 3 roadmap

1. Approve the pre-warm trigger/source and sale-timing contract; pre-warm must
   initialize stock and revision keys compatibly with revision fencing.
2. Add property-based stock correctness tests.
3. Add any remaining regression/operational tests approved after the durable
   authority gate; Redis Cluster topology testing remains a separate scope.
4. Reconcile `PROJECT_TRUTH.md`, `REPOSITORY_INDEX.md`, and obsolete Build Plan
   and Database Schema statements.

Week 3 remains in progress. Kafka, Inventory REST, Reservation/Week 4,
cross-request idempotency, retries, release, and reconciliation remain excluded.

---

## SESSION-007

**Date:** 2026-08-05
**Milestone:** Week 3 — Property-Based Stock Correctness Tests
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**Implementation commit:** `f12d67d7d7021e6620a6d35592c2e0ec0d5953b9`
**Commit status:** Pushed; local `main`, `HEAD`, and `origin/main` identify the
same implementation commit.

---

### 1. Implemented Scope

The independent Property-Based Stock Correctness Tests slice is complete.
jqwik 1.9.0 was added to InventoryService in test scope only, and a new domain
property-test class exercises the existing Product-owned StockLevel decrement
behavior without changing production code, orchestration, transaction
boundaries, PostgreSQL durable authority, or Redis revision fencing.

The new suite contains five properties with 1,000 generated examples each:

1. Successful positive decrements reduce stock by exactly the requested
   quantity and never produce negative stock.
2. Requests greater than available stock return insufficient stock and leave
   the counter unchanged.
3. A request equal to available stock depletes the counter exactly to zero.
4. Repeated generated operations match an independent arithmetic state model.
5. After exact depletion, subsequent positive requests remain insufficient and
   leave stock at zero.

Generators include zero, one, exact-depletion values, insufficient requests,
repeated sequences, and values near `Integer.MAX_VALUE`. Bounds and oracle
arithmetic avoid integer overflow.

---

### 2. Implementation Files Changed

- `services/inventory-service/build.gradle`
  - Added `net.jqwik:jqwik:1.9.0` as `testImplementation` only.
- `services/inventory-service/src/test/java/com/flashsale/inventory/domain/aggregate/ProductStockCorrectnessPropertyTest.java`
  - Added the five jqwik properties and their generators/model oracle.

No production file and no pre-existing test file changed in the implementation
slice.

---

### 3. Verification Results

Focused property suite:

```bash
./gradlew :services:inventory-service:test \
  --tests 'com.flashsale.inventory.domain.aggregate.ProductStockCorrectnessPropertyTest'
```

Result: `BUILD SUCCESSFUL` in 23 seconds. Five properties and 5,000 generated
examples passed.

InventoryService clean build:

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result: `BUILD SUCCESSFUL` in 30 seconds. All 131 Inventory tests passed with
zero failures, errors, or skips.

Whole-repository build:

```bash
./gradlew clean build
```

Result: `BUILD SUCCESSFUL` in 59 seconds. InventoryService passed 131 tests and
SaleService passed 16 tests, with zero failures, errors, or skips.

The Inventory total comprises 110 unit/property tests and 21 real
PostgreSQL/Redis Testcontainers tests across 18 runnable test classes.

Additional verification:

- jqwik is present on Inventory's test runtime classpath only and absent from
  its production runtime classpath.
- `git diff --check` passed.
- The production-code diff was empty.
- The tracked diff for all pre-existing tests was empty.
- The transient jqwik database file was absent from the committed tree.
- Independent implementation review concluded `APPROVED`.

The first sandboxed Gradle attempt could not acquire the user Gradle cache lock;
the identical verification was rerun with approved cache access and succeeded.

---

### 4. Build and Commit Status

The focused test, Inventory clean build, and complete repository build are all
successful. Implementation commit `f12d67d` is already pushed to `origin/main`.

---

### 5. Remaining Week 3 Work

1. Approve and implement the pre-warm use case, including the trigger/source,
   sale-timing contract, TTL ownership, and revision initialization compatible
   with the active Redis projection fence.
2. Preserve the 131-test regression baseline through any subsequently approved
   Week 3 slice.
3. Reconcile `context/PROJECT_TRUTH.md`, `context/REPOSITORY_INDEX.md`, and the
   obsolete Build Plan and Database Schema statements with current repository
   reality.

Property-based stock correctness testing is complete and is not remaining
scope. Kafka, Inventory REST, Reservation/Week 4, cross-service integration,
performance optimization, refactoring, retries, release, and reconciliation
implementation remain out of scope.

## SESSION-008

**Date:** 2026-08-06
**Milestone:** Week 3 — ADR-020 Architecture Review and Revision
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**Documentation commit:** `614d2cd` (`docs(adr): revise ADR-020 after architecture review adjudication`)
**Commit status:** Pushed; local `main`, `HEAD`, and `origin/main` identify the
same commit.

---

### 1. Implemented Scope

This session was documentation-only. No production code, test code, Lua
scripts, SQL, Gradle configuration, or application properties were changed.

ADR-020 (Pre-Warm Architecture) was revised from its initial draft to Revision 2
following an independent architecture review that produced six findings, all of
which were adjudicated before this session began.

Six findings were resolved:

1. **T-60 inconsistency** — §2 and §5 now distinguish `preWarmAt` as the
   readiness deadline from the execution window; the scheduler must fire before
   the deadline, not at it.
2. **Partial-pair rollback risk** — §10 and §13 add an explicit revision guard:
   a partial-pair repair is only permitted when the incoming revision is not
   older than the surviving key's revision.
3. **Missing-key stale recreation** — §13 adds a convergence paragraph defining
   that the post-commit projection synchronization is the correction mechanism
   and that `MISSED_WINDOW` bounds the stale window to pre-`saleStart`.
4. **TTL not in Redis state machine** — §8 adds TTL integrity semantics
   (persistent or inconsistent TTL = fail closed); §13 table extends the
   invalid-state row to cover missing or inconsistent TTLs.
5. **ADR-017 not amended (partially valid)** — Decision context now explicitly
   states the ADR amends ADR-017 to register InventoryService as a consumer of
   pre-warm-due events on `sale-events`.
6. **Terminal/retryable outcomes undefined** — §10 appends an outcome
   classification block covering terminal and retryable outcomes for transport
   acknowledgement, explicitly excluding retry policy, backoff, and DLQ.

---

### 2. Files Changed

- `docs/adr/20-Pre-Warm-Architecture.md` — ADR-020 Revision 2 (Decision context,
  §2, §5, §8, §10, §13)

No other file was changed in the ADR revision slice.

---

### 3. Verification

Independent verification reviewed ADR-020 Revision 2 against all six findings
and confirmed each was resolved. Verdict: **ADR REVISION 2 APPROVED**.

No build verification is applicable; this was a documentation-only slice.

---

### 4. Build and Commit Status

Implementation remains at `f12d67d` (unchanged). Documentation HEAD is
`614d2cd`, already pushed to `origin/main`. The repository build baseline of
131 Inventory tests and 16 SaleService tests remains unaffected.

---

### 5. Remaining Week 3 Work

1. Implement the pre-warm use case against the now-approved ADR-020 Revision 2
   contract: trigger/source, sale-timing window, authoritative snapshot load,
   revision-compatible Lua initialization, and TTL derivation.
2. Preserve the 131-test regression baseline through any subsequently approved
   Week 3 slice.
3. Reconcile `context/PROJECT_TRUTH.md`, `context/REPOSITORY_INDEX.md`, and the
   obsolete Build Plan and Database Schema statements with current repository
   reality.

The ADR-020 architecture design is approved and complete. Kafka, Inventory REST,
Reservation/Week 4, cross-service integration, release, and reconciliation
implementation remain out of scope.

---

## SESSION-009

**Date:** 2026-08-06
**Milestone:** Week 3 — Pre-Warm Use Case Implementation
**Outcome:** IMPLEMENTATION COMPLETE — PENDING COMMIT
**Engineer:** Tarun K Y
**Branch:** `main`
**Starting HEAD:** `84d68ab` (`docs: synchronize project state after ADR-020 Revision 2`)
**Implementation commit:** None — implementation exists in the working tree, not yet committed

---

### 1. Objective and governing scope

Implement the approved pre-warm use case slice per the ADR-020 Revision 2 contract.

Explicitly included:
- `stock-prewarm.lua` — full rewrite (revision-fenced, both keys, all ADR-020 §13 rows)
- `PreWarmStockResult` enum
- `StockPreWarmPort` application port
- `StockPreWarmUnavailableException`
- `PreWarmStockUseCase` service
- `StockPreWarmLuaExecutor` infrastructure executor
- `RedisStockPreWarmAdapter` infrastructure adapter
- `InventoryConfiguration` (`Clock` bean)
- `RedisScriptConfiguration` update (`stockPreWarmScript` bean)
- Unit and integration tests (+32 tests)

Explicitly excluded: Kafka consumer, `@Scheduled` trigger, REST endpoint, DTOs,
Reservation, Release, SaleService changes, Flyway migrations, and any scope beyond
ADR-020 §14–§15 application and infrastructure contracts.

Scope boundary decision before coding:
- Kafka consumer deferred (HANDOFF.md Rule 23 is an explicit gate constraint)
- `@Scheduled` trigger deferred
- Entry point: `PreWarmStockUseCase.preWarm(ProductId, SaleId, Instant saleStart, Instant saleEnd)`, callable by tests or a future adapter

---

### 2. Lua script

The pre-existing `stock-prewarm.lua` was incompatible with ADR-020 Revision 2
(marker-based, no revision key, caller-supplied stock). It was rewritten entirely.

New contract:
- `KEYS[1] = stock:{saleId}`, `KEYS[2] = stock:version:{saleId}`
- `ARGV[1] = stock`, `ARGV[2] = revision`, `ARGV[3] = TTL ms`
- Returns: `1 = WARMED`, `2 = UPDATED`, `3 = ALREADY_CURRENT`, `4 = STALE_IGNORED`, `-1 = INVALID_STATE`

All seven rows of the ADR-020 §13 interaction table are implemented.
`normalize_non_negative_integer` and `compare_non_negative_integers` follow the
pattern from `stock-projection-sync.lua`. `PEXPIRETIME` requires Redis 7.0+;
test infrastructure uses Redis 7.2.

`UPDATED` path: `SET KEYS[1] ARGV[1] KEEPTTL` preserves the existing absolute
expiration on the stock key; `SET KEYS[2] ARGV[2]` then `PEXPIREAT KEYS[2] stock_expiry`
mirrors it to the version key.

---

### 3. Architectural decisions

- ADR-020 §10 "invalid sale windows": `saleEnd <= saleStart` throws
  `IllegalArgumentException` at use-case entry, before any clock, DB, or Redis access.
- ADR-020 §5 timing: `PRE_WARM_WINDOW = 60s`; `NOT_DUE` before window; `MISSED_WINDOW`
  at or after `saleStart`.
- ADR-020 §8 TTL: derived as `saleEnd + 10 minutes − now` inside InventoryService; never
  accepted from caller.
- ADR-020 §7 revision source: `stockLevel.version()` from the same PostgreSQL snapshot;
  never assumed zero.
- `INVALID_STATE` Lua return (`-1`) is terminal: returned as `PreWarmStockResult.INVALID_STATE`,
  not thrown. Transport/null/unknown results throw `StockPreWarmUnavailableException`.
- `Clock` injected through `InventoryConfiguration.systemUtcClock()`.
- Partial-pair stock-key-only → `INVALID_STATE` (no revision available; fail closed).
- Partial-pair version-key-only with equal/newer incoming revision → repair with new TTL (`WARMED`).

---

### 4. New and modified production files

New:

| File | Responsibility |
|---|---|
| `application/PreWarmStockResult.java` | Seven-value result enum |
| `application/PreWarmStockUseCase.java` | Timing, snapshot load, TTL derivation, delegation |
| `application/port/StockPreWarmPort.java` | Infrastructure-neutral pre-warm outbound contract |
| `application/port/StockPreWarmUnavailableException.java` | Retryable transport failure signal |
| `infra/redis/StockPreWarmLuaExecutor.java` | Hash-tagged keys, TTL serialization, script execution |
| `infra/redis/RedisStockPreWarmAdapter.java` | Lua result mapping, transport failure translation |
| `infra/config/InventoryConfiguration.java` | `Clock.systemUTC()` bean |

Modified:

| File | Change |
|---|---|
| `resources/lua/stock-prewarm.lua` | Full rewrite — revision-fenced, dual keys, ADR-020 §13 |
| `infra/config/RedisScriptConfiguration.java` | Added `stockPreWarmScript` singleton bean |

---

### 5. New and modified test files

New:

| Test class | Tests | Coverage |
|---|---:|---|
| `StockPreWarmLuaExecutorTest` | 6 | Parameterized raw results (5), invalid input rejection (1) |
| `RedisStockPreWarmAdapterTest` | 9 | Parameterized result mapping (5), null/unknown/transport/serialization (4) |
| `PreWarmStockUseCaseTest` | 9 | saleEnd>saleStart, NOT_DUE, MISSED_WINDOW variants, product/stockLevel absence, delegation with correct TTL, all port results, nulls |
| `StockPreWarmIntegrationTest` | 7 | Real Redis: WARMED, ALREADY_CURRENT, STALE_IGNORED, UPDATED, partial-pair repair, partial-pair fail-closed, TTL preservation |
| **Added** | **+32** | |

Modified:

| Test class | Change |
|---|---|
| `RedisScriptConfigurationTest` | Added `loadsPreWarmScriptAsSingletonWithRevisionFencedContract` |

---

### 6. Implementation review and VALID finding

An independent review classified all findings as VALID, PARTIALLY VALID, or INVALID.

**One VALID finding was identified and resolved:**

`PreWarmStockUseCase.preWarm()` did not validate `saleEnd > saleStart`. If
`saleEnd < saleStart` but `saleEnd + 10 minutes > now`, TTL was positive, timing
checks passed, and execution proceeded with invalid sale data. ADR-020 §10 names
"invalid sale windows" as terminal deterministic failures.

**Fix:** Added `if (!saleEnd.isAfter(saleStart)) throw new IllegalArgumentException(...)`
after null checks. Removed `returnsMissedWindowWhenTtlIsNonPositive` test (its scenario
required `saleEnd < saleStart`, now caught upfront; the non-positive TTL guard is
unreachable with valid inputs inside the execution window). Added
`throwsWhenSaleEndNotAfterSaleStart` test.

Post-fix review verdict: **IMPLEMENTATION APPROVED**.

---

### 7. Verification

```bash
./gradlew :services:inventory-service:test
BUILD SUCCESSFUL
163 tests passed, 0 failed, 0 errors, 0 skipped

./gradlew build
BUILD SUCCESSFUL in 23s
InventoryService: 163 passed; SaleService: 16 passed; 0 failed, 0 errors, 0 skipped
```

| Inventory test category | Classes | Tests |
|---|---:|---:|
| Unit / property | 19 | 135 |
| PostgreSQL/Redis Testcontainers integration | 4 | 28 |
| **Total** | **23** | **163** |

---

### 8. Commit status

No commit was made this session. All implementation files are in the working tree:

- Untracked new production files: `PreWarmStockResult.java`, `StockPreWarmPort.java`,
  `StockPreWarmUnavailableException.java`, `PreWarmStockUseCase.java`,
  `StockPreWarmLuaExecutor.java`, `RedisStockPreWarmAdapter.java`, `InventoryConfiguration.java`
- Untracked new test files: `StockPreWarmLuaExecutorTest.java`,
  `RedisStockPreWarmAdapterTest.java`, `PreWarmStockUseCaseTest.java`,
  `StockPreWarmIntegrationTest.java`
- Modified: `stock-prewarm.lua`, `RedisScriptConfiguration.java`, `RedisScriptConfigurationTest.java`

---

### 9. Remaining Week 3 work

1. **Commit the pre-warm implementation** — all untracked/modified files currently in
   the working tree.
2. **Regression maintenance** — retain the 163-test baseline through any subsequent slice.
3. **Documentation reconciliation** — update `context/PROJECT_TRUTH.md` and
   `context/REPOSITORY_INDEX.md`; record obsolete Build Plan and Database Schema
   statements; mark Week 3 complete only after pre-warm is committed and documentation
   is reconciled.

Kafka integration, Inventory REST endpoints, Reservation/Week 4, release, and
reconciliation remain excluded.

---

## SESSION-010
**Date:** 2026-08-06
**Milestone:** Week 3 — Documentation Reconciliation
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**No implementation commit** — documentation-only session.

---

### Objective

Reconcile context documents with repository reality after the pre-warm implementation
was committed at `7b68f14` in SESSION-009 (working-tree commit). The pre-warm files
were present but uncommitted when SESSION-009 ended; they were committed in the same
session under commit `7b68f14 implemented redis-pre-warm`.

---

### Changes made

| File | Change |
|---|---|
| `HANDOFF.md` | Updated `Latest commit` to `7b68f14`; removed "pending commit" and "untracked files" language; replaced "Remaining Week 3 Tasks" with completed-task table |
| `context/CURRENT_STATE.md` | Status `🟡 IN PROGRESS` → `✅ COMPLETE`; updated commit reference; removed "pending commit" from Pre-Warm Use Case row; replaced remaining-work list with completed checkmarks; replaced "Next Recommended Task" with "Week 3 Status: COMPLETE" |
| `context/PROJECT_TRUTH.md` | Added staleness banner (Version 3); updated service table (SaleService → COMPLETE Week 2, InventoryService → COMPLETE Week 3); updated technology table runtime-detail for Java, Spring Boot, Gradle, jqwik, Testcontainers, Flyway |
| `context/REPOSITORY_INDEX.md` | `services/` and `build.gradle`/`settings.gradle` root entries updated from "Planned" to "Existing"; `services/` section expanded with SaleService and InventoryService subsections including production packages, test packages, and resources |
| `context/SESSION_LOG.md` | This SESSION-010 append |

---

### No code changes

Zero production Java files, test files, Lua scripts, Flyway migrations, or Gradle
files were modified. `./gradlew :services:inventory-service:cleanTest :services:inventory-service:build`
passes with `BUILD SUCCESSFUL`, 163 tests, 0 failed, 0 errors, 0 skipped.

---

### Week 3 final state

| Slice | Commit |
|---|---|
| InventoryService skeleton | `0444c9b` |
| Framework-free domain model | `213570a` |
| Persistence, Flyway V1, Redis Lua, Redis adapter | `eecc75c` |
| ProductRepository port and StockCounterService | `2a22457` |
| PostgreSQL fallback | `9bb3ad7` |
| Redis re-warming | `10069d8` |
| Durable authority, revision-fenced projection, infrastructure tests | `bca1ff1` |
| jqwik property-based stock correctness tests | `f12d67d` |
| Pre-warm use case (ADR-020 Revision 2) | `7b68f14` |
| Documentation reconciliation | SESSION-010 (no code commit) |

---

## SESSION-011
**Date:** 2026-08-06
**Milestone:** Week 4 — Reservation Domain Aggregate (Slice 1)
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**Implementation commit:** `713d2d2` (`feat(inventory): implement reservation domain aggregate`)
**Documentation commit:** pending (this reconciliation session)

---

### Objective

Implement the Reservation domain aggregate as Week 4, Slice 1, then reconcile
all context documents to reflect the completed slice. No persistence, REST,
Redis, Kafka, or scheduler work was included in this slice.

---

### Implementation contract

The approved Implementation Contract was produced in the same session. It was
reviewed twice (initial review + adversarial review post-implementation). Both
reviews returned 0 VALID, 0 PARTIALLY VALID findings. Contract approved.

---

### New production files (commit `713d2d2`)

| File | Responsibility |
|---|---|
| `domain/aggregate/Reservation.java` | Aggregate root: nested sealed `Status` (Pending/Confirmed/Expired/Released), `create`/`reconstitute` factories, `confirm`/`expire`/`release` commands, in-place mutation with `Math.incrementExact` |
| `domain/vo/ReservationId.java` | UUID-backed typed identity with `generate()` |
| `domain/vo/UserId.java` | Opaque UUID reference (no `generate()`; userId comes from external services) |
| `domain/vo/OrderId.java` | Opaque UUID reference (no `generate()`; orderId comes from OrderService) |
| `domain/vo/Quantity.java` | Strictly positive (≥1) reservation quantity |
| `domain/vo/ReservationExpiry.java` | Expiry instant with `in()` factory, `isExpired()`, `remainingTtl()` |

---

### New test files (commit `713d2d2`)

| Test class | Tests | Coverage |
|---|---:|---|
| `ReservationValueObjectTest` | 18 | ReservationId (4), UserId (3), OrderId (3), Quantity (3), ReservationExpiry (5) |
| `ReservationTest` | 29 | create (7), confirm (5), expire (4), release (5), version isolation (2), reconstitute (3) |
| **Added** | **47** | |

---

### Adversarial review

All 4 findings classified INVALID:
- F-1: Missing `isValid()` on `ReservationExpiry` — not required by contract; YAGNI.
- F-2: Nested sealed `Status` vs. top-level — permitted; contract cited §10 pattern only.
- F-3: Null expiry caught by dereference — effective; no explicit `requireNonNull` required by contract.
- F-4: `release(reason)` discards reason — correct; reason is not an aggregate field per DomainModel.md.

---

### Verification

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL in 47s
210 tests passed, 0 failed, 0 errors, 0 skipped
```

| Inventory test category | Classes | Tests |
|---|---:|---:|
| Unit / property | 25 | 182 |
| PostgreSQL/Redis Testcontainers integration | 4 | 28 |
| **Total** | **29** | **210** |

No existing files were modified. Scope check: only the 8 listed files created.

---

### Documentation reconciliation (this session)

| File | Change |
|---|---|
| `HANDOFF.md` | Milestone → Week 4; latest commit → `713d2d2`; removed "No Reservation/Week 4 model exists"; updated current-state paragraph (50 files, 28 classes, 210 tests); added Reservation domain files to Modified Files; updated build section; added Week 4 Completed Slices table |
| `context/CURRENT_STATE.md` | Status → IN PROGRESS; latest commit → `713d2d2`; updated file/class/test counts; added Reservation Domain Aggregate row to Completed Work; updated Database section; updated Architecture Locked section; added Week 4 Status block |
| `context/REPOSITORY_INDEX.md` | Updated status line and commit; `domain.aggregate` 1→2; `domain.vo` 4→9; `domain.*` test classes 5→7 |
| `context/PROJECT_TRUTH.md` | InventoryService service table row updated (IN PROGRESS, 50 files, 210 tests, `713d2d2`); Domain Model status note updated |
| `context/SESSION_LOG.md` | This SESSION-011 append |

---

### Week 4 slice status

| Slice | Status |
|---|---|
| Slice 1: Reservation domain aggregate | ✔ DONE — `713d2d2` |
| Slice 2: Reservation persistence | NOT STARTED |
| Slice 3: REST `POST /api/v1/reservations` | NOT STARTED |
| Slice 4: Redis duplicate guard | NOT STARTED |
| Slice 5: `stock_release.lua` integration | NOT STARTED |
| Slice 6: Expiry sweep `@Scheduled` | NOT STARTED |
| Slice 7: Kafka events | NOT STARTED |
| Slice 8: 1500-concurrent integration test | NOT STARTED |
| Slice 9: `ReservationCommandService` | NOT STARTED |

Week 3: **COMPLETE**.

---

## SESSION-012
**Date:** 2026-08-11
**Milestone:** Week 4 — Reservation Persistence (Slice 2)
**Outcome:** COMPLETE
**Engineer:** Tarun K Y
**Branch:** `main`
**Implementation commit:** `683efe4` (`feat(inventory): add reservation persistence`)
**Documentation commit:** pending (this reconciliation session)

---

### Objective

Implement Reservation persistence as Week 4, Slice 2: V2 Flyway migration,
JPA entity, persistence mapper, Spring Data repository, application port,
infrastructure adapter, and tests. Then perform adversarial review and apply
accepted findings.

---

### New production files (commit `683efe4`)

| File | Responsibility |
|---|---|
| `db/migration/V2__add_reservations.sql` | `reservations` and `stock_reservation_log` tables; CHECK constraints; partial UNIQUE index `idx_reservations_user_sale_active`; pending-expiry index |
| `application/port/ReservationRepository.java` | Application port: `findById(ReservationId)`, `save(Reservation)` |
| `infra/persistence/ReservationJpaEntity.java` | Separate JPA entity; `@Version`; `updatable=false` on immutable fields; `updateStatus`/`updateOrderId` package-private mutators |
| `infra/persistence/ReservationPersistenceMapper.java` | Domain ↔ JPA translation; status via class simple name; null-safe orderId |
| `infra/persistence/SpringDataReservationRepository.java` | Plain `JpaRepository<ReservationJpaEntity, UUID>` |
| `infra/persistence/ReservationRepository.java` (adapter) | Load-then-update `save()` to avoid `@Version` conflict on domain-incremented version |

---

### New test files (commit `683efe4`)

| Test class | Tests | Coverage |
|---|---:|---|
| `ReservationPersistenceMapperTest` | — | mapper round-trips, null orderId |
| `ReservationRepositoryAdapterTest` | 6 | load, missing, save (INSERT path), save (UPDATE path — F-3), null guards |
| `ReservationPersistenceIntegrationTest` | 8 | Flyway V2, round-trip, all 4 status transitions, unique index enforcement, expired-unblocks-new |

---

### Adversarial review

| Finding | Classification | Action |
|---|---|---|
| F-1: `resetInfrastructure` missing `DELETE FROM stock_reservation_log` | VALID | Applied — prevents FK violation when stock log write path is added |
| F-2: INSERT path 3 DB roundtrips (SELECT + SELECT + INSERT) | VALID | **Rejected** — optimization-only; no correctness bug; no contract requirement; outside approved scope |
| F-3: No unit test for UPDATE path in adapter | VALID | Applied — `saveUpdatesExistingEntityInPlaceWhenFoundById` added |
| F-4: nullable `idempotency_key` UNIQUE constraint | PARTIALLY VALID | No action — intentional scope deferral to Slice 3 |

---

### Verification

```bash
./gradlew :services:inventory-service:test
BUILD SUCCESSFUL in 33s
241 tests passed, 0 failed, 0 errors, 0 skipped
```

No production files outside approved Slice 2 scope were modified.
Reservation domain aggregate (`Reservation.java`) unchanged.

---

### Documentation reconciliation (this session)

| File | Change |
|---|---|
| `context/CURRENT_STATE.md` | Latest commit → `683efe4`; counts updated; Slice 2 row added to Completed Work; Database section updated; Architecture Locked updated; Week 4 Status updated |
| `HANDOFF.md` | Slice 2 status → COMPLETE; latest commit updated; counts updated; Slice 2 added to Week 4 Completed Slices; remaining slices list updated; new production/test files listed |
| `context/PROJECT_TRUTH.md` | InventoryService row and Migrations row updated |
| `context/SESSION_LOG.md` | This SESSION-012 append |

---

### Week 4 slice status

| Slice | Status |
|---|---|
| Slice 1: Reservation domain aggregate | ✔ DONE — `713d2d2` |
| Slice 2: Reservation persistence | ✔ DONE — `683efe4` |
| Slice 3: REST `POST /api/v1/reservations` | NOT STARTED |
| Slice 4: Redis duplicate guard | NOT STARTED |
| Slice 5: `stock_release.lua` integration | NOT STARTED |
| Slice 6: Expiry sweep `@Scheduled` | NOT STARTED |
| Slice 7: Kafka events | NOT STARTED |
| Slice 8: 1500-concurrent integration test | NOT STARTED |
| Slice 9: `ReservationCommandService` | NOT STARTED |

---

## SESSION-013
**Date:** 2026-08-11
**Milestone:** Week 4, Slice 3 — REST `POST /api/v1/reservations` + Command Service + Redis Duplicate Guard
**Outcome:** COMPLETE (uncommitted working tree)
**Engineer:** Tarun K Y

---

### Objective

Implement Week 4 Slice 3: `POST /api/v1/reservations` endpoint, `ReservationCommandService`, Redis SET NX EX duplicate guard, V3 Flyway migration (idempotency_key NOT NULL), and all specified tests. Scope boundary: no SALE_NOT_ACTIVE, no Lua scripts, no expiry sweep, no Kafka events.

---

### Implementation

#### New files (production)

| File | Purpose |
|---|---|
| `db/migration/V3__reservation_idempotency_key_not_null.sql` | Makes `idempotency_key NOT NULL` |
| `application/CreateReservationCommand.java` | Command record (idempotencyKey, userId, saleId, productId, quantity) |
| `application/ReservationCreatedResult.java` | Sealed interface (Created, IdempotentReplay, SoldOut, DuplicateReservation) |
| `application/ReservationCommandService.java` | Orchestrates idempotency check → Redis NX guard → stock decrement → persist |
| `application/port/ReservationDuplicateGuardPort.java` | Port: `boolean tryAcquire(UserId, SaleId)` |
| `application/port/ReservationDuplicateGuardUnavailableException.java` | Signal for Redis transport indeterminate |
| `infra/redis/RedisReservationDuplicateGuardAdapter.java` | SET NX EX 30s; key `resv:lock:{userId}:{saleId}`; falls through on unavailability |
| `api/ReservationController.java` | `POST /api/v1/reservations`; 201/200/409/400 |
| `api/InventoryExceptionHandler.java` | `@RestControllerAdvice`; SOLD_OUT/DUPLICATE_RESERVATION/MISSING_HEADER/VALIDATION_FAILED |
| `api/dto/CreateReservationRequest.java` | Bean-validated request record |
| `api/dto/ReservationResponse.java` | Response record |
| `api/dto/ErrorResponse.java` | Error record |

#### Modified files (production)

| File | Change |
|---|---|
| `domain/aggregate/Reservation.java` | Added `idempotencyKey` field; 7-param `create()`; `reconstitute()` updated to 10 params |
| `infra/persistence/ReservationJpaEntity.java` | Added `idempotency_key` column (nullable=false, updatable=false) |
| `infra/persistence/ReservationPersistenceMapper.java` | Maps `idempotencyKey` in both directions |
| `application/port/ReservationRepository.java` | Added `findByIdempotencyKey(String)` |
| `infra/persistence/SpringDataReservationRepository.java` | Added `findByIdempotencyKey` Spring Data method |
| `infra/persistence/ReservationRepository.java` (adapter) | Implements `findByIdempotencyKey` |
| `build.gradle` | Added `spring-boot-starter-validation` |

#### New test files

| File | Coverage |
|---|---|
| `application/ReservationCommandServiceTest.java` | Happy path, idempotent replay, sold-out, Redis duplicate, Redis unavailable fall-through |
| `api/ReservationControllerTest.java` | 201, 200 replay, 400 missing header, 400 validation, 409 SOLD_OUT, 409 DUPLICATE_RESERVATION |
| `infra/redis/RedisReservationDuplicateGuardAdapterTest.java` | setIfAbsent true/false, null result, connection failure, timeout, key format |
| `integration/ReservationCommandIntegrationTest.java` | First create, idempotent replay, two users same sale, V3 NOT NULL verified |

#### Modified test files

| File | Change |
|---|---|
| `domain/aggregate/ReservationTest.java` | Added null as 10th param to `reconstitute()` calls |
| `infra/persistence/ReservationPersistenceMapperTest.java` | Updated `reconstitute()`/`ReservationJpaEntity()` calls; added idempotencyKey mapping tests |
| `infra/persistence/ReservationRepositoryAdapterTest.java` | Updated stubs; added `findByIdempotencyKey` tests |
| `integration/ReservationPersistenceIntegrationTest.java` | `newPendingReservation()` uses 7-param `create()`; added `findByIdempotencyKey` tests |

---

### Build gate

```text
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL in 50s
268 tests passed, 0 failed, 0 errors, 0 skipped
```

---

### Scope verification

- No Spring/JPA imports in domain — PASS
- No Kafka/Lua/expiry sweep — PASS
- No infra imports in application layer — PASS
- Redis key shape `resv:lock:{userId}:{saleId}` — PASS
- Lua files unchanged — PASS
- V3 migration only new SQL — PASS
- SaleService untouched — PASS
- V1/V2 migrations unchanged — PASS
- `StockCounterService` unchanged — PASS

---

### Documentation reconciliation

| File | Change |
|---|---|
| `HANDOFF.md` | Slice 3 status → COMPLETE; working tree note updated |
| `context/CURRENT_STATE.md` | Counts updated; Slice 3 row added; Database section updated |
| `context/SESSION_LOG.md` | This SESSION-013 append |

---

### Week 4 slice status

| Slice | Status |
|---|---|
| Slice 1: Reservation domain aggregate | ✔ DONE — `713d2d2` |
| Slice 2: Reservation persistence | ✔ DONE — `683efe4` |
| Slice 3: REST + Command Service + Redis guard | ✔ DONE — uncommitted |
| Slice 4: `stock_release.lua` integration + expiry sweep | ✔ DONE — uncommitted |
| Slice 5: Kafka events | NOT STARTED |
| Slice 6: 1500-concurrent integration test | NOT STARTED |

---

## SESSION-014
**Date:** 2026-08-11
**Milestone:** Week 4 — Reservation (in progress)
**Outcome:** COMPLETE — Slice 4 implementation verified
**Engineer:** Tarun K Y

---

### Objective

Implement Week 4 Slice 4: integrate the pre-existing `stock-release.lua` Lua script with a
new `StockReleasePort` adapter stack, and implement the `ReservationExpiryService` 30-second
scheduled sweep that expires PENDING reservations past their `expiresAt` timestamp and
best-effort releases Redis stock.

---

### Pre-implementation conflict resolved

**CONFLICT-NEW-001 — `stock-release.lua` missing KEEPTTL**

The pre-existing script contained `redis.call('SET', KEYS[1], newStock)`, which resets the
Redis key TTL on every invocation. Because the sale's stock key is `stock:{saleId}` with a
TTL tied to sale duration, each expiry sweep call would have silently reset that TTL,
effectively extending the sale indefinitely in Redis.

Resolution: changed to `redis.call('SET', KEYS[1], newStock, 'KEEPTTL')`.
User explicitly approved this change before implementation began.

---

### Implementation summary

#### Modified files

| File | Change |
|---|---|
| `lua/stock-release.lua` | `SET KEYS[1] newStock` → `SET KEYS[1] newStock KEEPTTL` (approved CONFLICT-NEW-001) |
| `infra/config/RedisScriptConfiguration.java` | Added `stockReleaseScript()` bean (singleton `DefaultRedisScript<Long>` loading `stock-release.lua`) |
| `infra/config/InventoryConfiguration.java` | Added `@EnableScheduling` |
| `application/port/ReservationRepository.java` | Added `findExpiredPending(Instant now)` |
| `infra/persistence/SpringDataReservationRepository.java` | Added JPQL `findExpiredPending(@Param("now") Instant)` |
| `infra/persistence/ReservationRepository.java` | Implemented `findExpiredPending(Instant)` delegating to Spring Data, mapping to domain |
| `infra/config/RedisScriptConfigurationTest.java` | Added `loadsReleaseScriptAsSingletonWithKeepTtlAndCeiling()` |

#### New production files

| Class | Package | Responsibility |
|---|---|---|
| `StockReleasePort` | `application.port` | `release(SaleId, int quantity, int ceiling) → StockReleaseResult` |
| `StockReleaseUnavailableException` | `application.port` | Application-owned Redis transport failure signal |
| `StockReleaseResult` | `application` | Enum: `RELEASED`, `SALE_ENDED` |
| `StockReleaseLuaExecutor` | `infra.redis` | Key `stock:{saleId}`, qty+ceiling args, executes `stockReleaseScript`, raw Long result |
| `RedisStockReleaseAdapter` | `infra.redis` | Implements `StockReleasePort`; maps `≥0→RELEASED`, `-2→SALE_ENDED`, null/transport/unknown→unavailable |
| `ReservationExpiryService` | `application` | `@Scheduled(fixedDelay=30_000)`: loads PENDING reservations expired by `clock.instant()`, calls `expire()`, saves to DB, best-effort `StockReleasePort.release()` |

#### New test files

| Test class | Tests |
|---|---:|
| `StockReleaseLuaExecutorTest` | 8 |
| `RedisStockReleaseAdapterTest` | 6 |
| `ReservationExpiryServiceTest` | 7 |
| `ReservationExpiryIntegrationTest` | 5 |
| `RedisScriptConfigurationTest` (new case) | 1 |
| **Slice 4 new tests** | **26** |

---

### Architecture decisions

- **DB-first expiry:** `reservation.expire()` and `reservationRepository.save()` execute
  before `StockReleasePort.release()`. PostgreSQL transition is authoritative; Redis release
  is advisory. A `StockReleaseUnavailableException` or `SALE_ENDED` never reverts the DB state.
- **Best-effort Redis release:** The sweep catches and logs all exceptions from `releaseStock()`
  without aborting the outer per-reservation loop. Other reservations in the same sweep batch
  are unaffected.
- **Ceiling from Product:** `totalAllocated` is read from the Product aggregate via
  `ProductRepository` port. Preserves hexagonal boundaries; never bypasses Product ownership.
- **No LIMIT on query:** `findExpiredPending` returns all eligible rows. If sweep latency
  becomes measurable, add batching (ponytail comment in service).
- **KEEPTTL contract:** `-2` (key absent) means the sale has ended or was never pre-warmed;
  treated as `SALE_ENDED` — no error, sweep continues. Non-negative result is `RELEASED`.

---

### Verification results

Focused Inventory build:

```text
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL in 35s
Tests: 294 passed, 0 failed, 0 errors, 0 skipped
```

Full repository build:

```text
./gradlew clean build
BUILD SUCCESSFUL in 52s
Inventory: 294 passed, 0 failed, 0 errors, 0 skipped
SaleService: 16 passed, 0 failed, 0 errors, 0 skipped
Total: 310
```

---

### Scope checks

- No new Flyway migrations — PASS (V3 exists from Slice 3; no V4 added)
- No REST changes — PASS
- No Kafka code — PASS
- No SaleService changes — PASS
- `domain` packages contain no Spring/JPA/Redis/Kafka imports — PASS
- `application` packages import only domain types and application ports — PASS
- KEEPTTL present in `stock-release.lua` — PASS
- `@EnableScheduling` on `InventoryConfiguration` — PASS
- `@Scheduled(fixedDelay = 30_000)` on `ReservationExpiryService.expireReservations()` — PASS

---

### Documentation reconciliation

| File | Change |
|---|---|
| `HANDOFF.md` | Slice 4 status added; next slice → Slice 5; Modified Files section updated; build counts updated |
| `context/CURRENT_STATE.md` | Slice 4 row added; test count → 294/310; Week 4 remaining slices updated |
| `context/SESSION_LOG.md` | This SESSION-014 append |

---

### Week 4 slice status

| Slice | Status |
|---|---|
| Slice 1: Reservation domain aggregate | ✔ DONE — `713d2d2` |
| Slice 2: Reservation persistence | ✔ DONE — `683efe4` |
| Slice 3: REST + Command Service + Redis guard | ✔ DONE — uncommitted |
| Slice 4: `stock_release.lua` integration + expiry sweep | ✔ DONE — uncommitted |
| Slice 5: Kafka events | NOT STARTED |
| Slice 6: 1500-concurrent integration test | NOT STARTED |

---

## SESSION-015
**Date:** 2026-08-12
**Milestone:** Week 4, Slice 5 — Inventory Transactional Outbox + Kafka Events
**Outcome:** COMPLETE — frozen contract verified; documentation reconciled
**Engineer:** Tarun K Y

---

### Implemented scope

- Added Flyway V4 `inventory_outbox` and infrastructure-only JPA persistence;
  no outbox state entered the Inventory domain.
- Added application events `StockReserved` and `ReservationExpired`.
- Persisted a successful reservation and its `StockReserved` outbox row in one
  PostgreSQL transaction, using PostgreSQL-authoritative `remainingStock`.
- Persisted the expiry transition and its `ReservationExpired` outbox row in
  one PostgreSQL transaction. Existing Redis restoration remains post-commit
  and cannot remove or change the committed event.
- Added a 500 ms publisher using batches of at most 100 rows selected in
  `created_at` order with `FOR UPDATE SKIP LOCKED` and held through publication.
- Added at-least-once `inventory-events` publication with a stable application-
  assigned `eventId`, Kafka key derived from persisted `payload.productId`, and
  batch failure metadata with indefinite later polling.
- Added only the InventoryService-owned `inventory-events` topic: 16 partitions,
  three-day retention, LZ4, production replication factor 3/min ISR 2, and the
  single-broker test equivalent replication factor 1/min ISR 1.
- The verified implementation/test change set was exactly 22 authorized files.

### Contract corrections and final audit

- Persisted `event_type` accepts exactly `StockReserved` or
  `ReservationExpired`; null, blank, and unknown values fail batch preparation
  before the first Kafka send, leaving rows unpublished and recording failure
  metadata.
- Kafka outage/recovery is verified against the same real Kafka Testcontainer
  broker endpoint. Recovery is not simulated by manually resetting
  `published=false`; the row remains unpublished during the outage, publishes
  after broker restart, and retains its original `eventId`.
- Final contract verdict: **PASS**. No forbidden or unrelated files were part of
  the 22-file Slice 5 implementation/test commit (`233ca84`).

### Verification

```text
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL
InventoryService: 318 passed, 0 failed, 0 errors, 0 skipped

./gradlew clean build
BUILD SUCCESSFUL
InventoryService: 318 passed, 0 failed, 0 errors, 0 skipped
SaleService:       16 passed, 0 failed, 0 errors, 0 skipped
Total:            334 passed, 0 failed, 0 errors, 0 skipped

git diff --check
PASS
```

### Documentation reconciliation

Canonical current-state, plan, schema, requirements, domain-event, and Kafka
references were updated only where the verified Slice 5 implementation made
their statements stale. The roadmap was not expanded: the next planned Week 4
slice remains the 1500-concurrent integration test for a 1000-unit sale.
