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
