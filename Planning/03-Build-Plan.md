# Build-Plan.md
## Flash Sale Platform — 10-Week Implementation Roadmap
**Version:** 1.0 | **Status:** Approved for execution
**Date:** 2026-06-15
**Owner:** Engineering Manager
**Sources:** Final-Spec-Council.md v2.0 · DomainModel.md · DatabaseSchema.md · KafkaDesign.md · RedisDesign.md

> **EM rule:** Each week produces working, runnable software.
> No week ends with infrastructure in place but no tests passing.
> A green test suite is the only definition of "done."

---

## Summary

| Metric | Value |
|---|---|
| Total weeks | 10 |
| Services built | 5 (SaleService, InventoryService, OrderService, NotificationService, AnalyticsService) |
| Total tasks | 38 |
| Blocked weeks | 0 |
| Phases | Foundation → Core Services → Integration → Resilience → Observability → Production Readiness |

## Dependency graph

```
Week 1 (infra)
  └── Week 2 (SaleService domain)
  └── Week 3 (InventoryService stock + Lua)
        └── Week 4 (Reservation aggregate)
              └── Week 6 (Kafka wiring)
  └── Week 5 (OrderService + Outbox)
        └── Week 6 (Kafka wiring)
              ├── Week 7 (SaleService Redis + rate limiter)
              ├── Week 8 (Retry + DLQ + NotificationService)
              └── Week 9 (AnalyticsService + observability)
                    └── Week 10 (Kubernetes + load test + prep)
```

---

## Week 1 — Infrastructure Foundation

**Phase:** Foundation
**Goal:** Every engineer can run the full infrastructure stack locally with a single command. No service code yet — just the platform everything else depends on.

### Objectives

- Docker Compose environment with all three Postgres instances, Redis Cluster, and Kafka running and healthy
- Flyway migrations applied automatically on container startup — no manual SQL steps
- Seed data present so week 2 has real data to test against

### Tasks

| # | Task | Area |
|---|---|---|
| 1.1 | Docker Compose: `sales_db`, `inventory_db`, `orders_db` on ports 5432/5433/5434 | Infra |
| 1.2 | Redis Cluster (3 shards, 1 replica each), AOF `everysec`, keyspace notify `Ex` | Infra |
| 1.3 | Kafka KRaft (no Zookeeper), Kafka UI on port 8080 | Infra |
| 1.4 | Flyway V1 migration scripts for all 3 schemas — tables, indexes, constraints as per DatabaseSchema.md | DB |
| 1.5 | `init.sql` seed: 2 products, 1 SCHEDULED sale, 100 users | DB |
| 1.6 | Health check scripts: `psql -c "SELECT 1"`, `redis-cli PING`, `kafka-topics --list` | Ops |

### Deliverables

- `docker compose up` → all services healthy in under 60 seconds
- `docker compose ps` shows all containers in `healthy` state
- Flyway `flyway info` shows `V1__init` applied on all 3 schemas
- `redis-cli -c CLUSTER INFO` shows `cluster_state:ok`
- Kafka UI at `localhost:8080` accessible, 0 topics (topics created by services)

### Dependencies

None. This week has no blockers. It is the root of all other work.

### Definition of Done

```
[ ] docker compose up completes in < 60s with no errors
[ ] All 3 Postgres schemas have correct tables per DatabaseSchema.md
[ ] Redis CLUSTER INFO reports cluster_state:ok
[ ] Kafka UI accessible at localhost:8080
[ ] Flyway shows V1 migration applied on all 3 databases
[ ] Seed data queryable: SELECT COUNT(*) FROM products = 2
```

### Interview question unlocked

> "How do you run a Kafka + Redis + Postgres stack locally without Kubernetes? Walk me through your docker-compose.yml. How do you handle three separate Postgres instances without port conflicts?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Redis Cluster mode complex to configure in Docker | Medium | Use Bitnami Redis Cluster image — pre-configured for cluster mode |
| Flyway migration ordering conflicts | Low | Single V1 file per schema, no inter-schema dependencies |
| Kafka KRaft unfamiliar vs Zookeeper | Low | Official Confluent KRaft Docker image; KRaft is stable in Kafka 3.x |

---

## Week 2 — SaleService Skeleton

**Phase:** Core Services
**Goal:** The first service is running, the FlashSale aggregate is implemented with Java 21 features, and the state machine is tested with both valid and illegal transitions.

### Objectives

- Spring Boot 3 SaleService with virtual threads enabled
- `FlashSale` aggregate root implemented with Java 21 sealed interface `SaleStatus`
- REST API for sale creation and retrieval
- Unit tests covering all state machine transitions

### Tasks

| # | Task | Area |
|---|---|---|
| 2.1 | Spring Boot 3 project: virtual threads via `spring.threads.virtual.enabled=true` | Service |
| 2.2 | `FlashSale` aggregate root with sealed interface `SaleStatus` (Scheduled/Active/Ended/Archived records) | Domain |
| 2.3 | `SaleSchedule` entity + `SaleWindow` value object with `isOpen()`, `isUpcoming()`, `hasPassed()` | Domain |
| 2.4 | `SaleRepository` (Spring Data JPA) + `SaleStatusHistory` append-only writes | Infra |
| 2.5 | `POST /api/v1/sales` and `GET /api/v1/sales/{id}` endpoints | API |
| 2.6 | Unit tests: all 4 valid transitions pass, 4 illegal transitions throw `IllegalStateException` | Test |

### Deliverables

- `POST /api/v1/sales` creates a `SCHEDULED` sale and returns `201 Created` with `saleId`
- `GET /api/v1/sales/{id}` returns sale with correct status and schedule
- `sale_status_history` has one entry for every status transition
- State machine unit tests: 8 tests passing (4 valid, 4 illegal)

### Dependencies

- Week 1: `sales_db` must exist with V1 migration applied

### Definition of Done

```
[ ] POST /api/v1/sales returns 201 with valid saleId
[ ] GET /api/v1/sales/{id} returns correct sale payload
[ ] Activating a SCHEDULED sale produces a sale_status_history row
[ ] Attempting ENDED → ACTIVE throws and returns 409
[ ] 8 unit tests pass covering all state machine paths
[ ] Virtual thread config verified: spring.threads.virtual.enabled=true in application.yml
```

### Interview question unlocked

> "Walk me through your Java 21 sealed interface for `SaleStatus`. Why sealed over an enum? What does pattern matching in a switch expression give you that an enum switch does not? What happens at compile time if you add a new status?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| JPA + sealed interface mapping complexity | Medium | Map status as `VARCHAR`, reconstruct sealed type in domain layer — never let JPA touch the sealed interface directly |
| Spring Boot 3 virtual thread config not taking effect | Low | Verify with `Thread.currentThread().isVirtual()` in a controller test |

---

## Week 3 — InventoryService: Product + Stock Counter

**Phase:** Core Services
**Goal:** The atomic stock decrement Lua script is running in Redis, the Postgres fallback path is tested by killing Redis, and a property-based test proves stock never goes negative under concurrent load.

### Objectives

- `InventoryService` with `Product` aggregate and `StockLevel` entity
- `stock_decrement.lua` loaded and executed via `RedisTemplate`
- Pre-warm scheduler sets Redis counter before sale start
- Postgres `SELECT FOR UPDATE` fallback path operational

### Tasks

| # | Task | Area |
|---|---|---|
| 3.1 | Spring Boot project: InventoryService with virtual threads | Service |
| 3.2 | `Product` aggregate + `StockLevel` entity + `StockCount` value object with `isAvailable()`, `isSoldOut()` | Domain |
| 3.3 | `stock_decrement.lua` loaded via `ClassPathResource`, SHA-cached by Spring | Redis |
| 3.4 | `stock_prewarm.lua` — idempotent pre-warm, `@Scheduled` 60s before `saleStart` | Redis |
| 3.5 | `StockCounterService.reserve()` routing: `-2` → Postgres fallback, `-1` → sold out, `≥ 0` → success | Service |
| 3.6 | Postgres `SELECT FOR UPDATE` fallback: load from `stock_levels`, decrement transactionally, re-warm Redis | Fallback |
| 3.7 | Property-based tests (jqwik): 1000 concurrent calls, stock never negative, return codes always valid | Test |

### Deliverables

- `redis-cli GET stock:{saleId}` shows correct integer after pre-warm
- Lua DECR returns `-1` when stock reaches 0 — `redis-cli GET` confirms `0`, never `-1`
- Kill Redis, verify reservations still succeed via Postgres path
- `stock_reservation_log.source` column shows `POSTGRES_FALLBACK` for fallback calls
- Property-based tests pass with 1000 random stock levels and quantities

### Dependencies

- Week 1: `inventory_db` with `products` and `stock_levels` tables
- Week 2: `SaleId` typed value object pattern as reference

### Definition of Done

```
[ ] stock_decrement.lua returns -2, -1, or >= 0 only — never other values
[ ] Pre-warm sets stock:{saleId} with correct TTL (saleEnd - now + 600s)
[ ] Second pre-warm by a racing pod is a no-op (stock:warmed:{saleId} NX guard)
[ ] Postgres fallback path covered by integration test (Testcontainers, Redis stopped)
[ ] Property-based test: 1000 inputs, 0 negative stock outcomes
[ ] stock_reservation_log written for every decrement with source field
```

### Interview question unlocked

> "Why Lua instead of `WATCH`/`MULTI`/`EXEC`? What happens under 50,000 concurrent `DECR` calls without atomicity? Walk me through each return code and what the caller must do for each."

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Lua script hash invalidated on Redis restart | Medium | Use `ScriptingCommands.scriptLoad()` on startup; Spring `DefaultScriptExecutor` handles SHA fallback automatically |
| Hash tag co-location misunderstood | Medium | Unit test verifying `stock:{saleId}` and `stock:warmed:{saleId}` land on the same shard (CRC16 check) |
| Property-based test framework unfamiliar (jqwik) | Low | Alternative: loop 1000 iterations in a plain JUnit test with `ThreadLocalRandom` |

---

## Week 4 — InventoryService: Reservation Aggregate

**Phase:** Core Services
**Goal:** The Reservation aggregate is live with its full lifecycle. The partial unique index enforces the "one active reservation per user per sale" invariant. The expiry sweep runs and releases stock.

### Objectives

- `Reservation` aggregate root separate from `Product`
- `POST /api/v1/reservations` is idempotent and concurrent-safe
- Expiry sweep releases stock and triggers compensation
- Concurrent reservation integration test proves exactly N reservations succeed

### Tasks

| # | Task | Area |
|---|---|---|
| 4.1 | `Reservation` aggregate: `PENDING → CONFIRMED / EXPIRED / RELEASED` state machine | Domain |
| 4.2 | `ReservationExpiry` value object with `isExpired()`, `remainingTtl()` | Domain |
| 4.3 | `POST /api/v1/reservations`: idempotency key check → Lua DECR → DB write (atomic) | API |
| 4.4 | `resv:lock:{userId}:{saleId}` Redis NX key — 30s guard against duplicate in-flight creation | Redis |
| 4.5 | Expiry sweep: `@Scheduled` every 30s, `UPDATE WHERE status=PENDING AND expires_at < NOW() RETURNING` | Scheduler |
| 4.6 | `stock_release.lua` called for every expired reservation — ceiling enforced | Redis |
| 4.7 | Integration test (Testcontainers): 1500 concurrent POST requests for 1000-unit sale | Test |

### Deliverables

- `POST /api/v1/reservations` returns `201` on first call, same `201` body on retry with same key
- Second PENDING reservation for same user + sale returns `409 DUPLICATE_RESERVATION`
- Expiry sweep transitions PENDING → EXPIRED, calls `stock_release.lua`, publishes `ReservationExpired`
- Integration test: 1500 requests → exactly 1000 `201` responses, 500 `409 SOLD_OUT`
- `idx_reservations_user_sale_active` partial unique index visible in `\d reservations` in psql

### Dependencies

- Week 3: `StockCounterService` and `stock_release.lua` must exist

### Definition of Done

```
[ ] POST /api/v1/reservations is idempotent: 5 retries with same key = 1 reservation row
[ ] Partial unique index prevents concurrent double-reservation (test with 2 threads, same user/sale)
[ ] Expiry sweep fires within 30s, changes status to EXPIRED, increments Redis stock
[ ] stock_release.lua ceiling prevents stock going above totalAllocated on replay
[ ] Integration test: 1000 stock, 1500 concurrent requests → exactly 1000 successes
[ ] ReservationExpiry.isExpired() tested with boundary values (1ms before, 1ms after expiry)
```

### Interview question unlocked

> "How does your partial unique index `idx_reservations_user_sale_active` prevent double-reservation at the database level? Why `WHERE status IN ('PENDING','CONFIRMED')` and not a full unique constraint? What happens to EXPIRED rows?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Race between Lua DECR and DB reservation write (non-atomic) | High | If DB write fails after DECR, the expiry sweep will restore stock within 30s. Document as known eventual consistency window |
| Testcontainers + 1500 threads OOM on CI | Medium | Reduce to 500 threads with 333 stock units; same correctness proof, lower resource requirement |
| Expiry sweep missing reservations at scale | Low | Use `LIMIT 500` + loop in scheduler to prevent single query holding lock too long |

---

## Week 5 — OrderService: Core + Idempotency

**Phase:** Core Services
**Goal:** The `Order` aggregate is implemented with the Transactional Outbox pattern. A retry with the same `Idempotency-Key` returns the original response without creating a duplicate order. The outbox row is written atomically with the order row.

### Objectives

- `Order` aggregate with `OutboxEvent` and `IdempotencyRecord` child entities
- Dual-layer idempotency: Redis `idem:{userId}:{key}` → Postgres fallback
- `POST /api/v1/orders` returns `202 Accepted` immediately
- Order + OutboxEvent written in one `@Transactional` block — no partial state possible

### Tasks

| # | Task | Area |
|---|---|---|
| 5.1 | Spring Boot project: OrderService with virtual threads | Service |
| 5.2 | `Order` aggregate: `PlaceOrder` command, `PENDING → CONFIRMED / CANCELLED / EXPIRED` | Domain |
| 5.3 | `IdempotencyRecord` entity: Redis `idem:{userId}:{key}` check → Postgres `idempotency_keys` fallback | Domain |
| 5.4 | `OutboxEvent` entity: written in same `@Transactional` block as `Order` — never separately | Domain |
| 5.5 | `POST /api/v1/orders`: `400` if no `Idempotency-Key` header, `202` on success | API |
| 5.6 | `IdempotencyKey` value object: 24h TTL contract, `isSameRequest()`, `isExpired()` | Domain |
| 5.7 | Integration test: 5 retries with same key → 1 `orders` row, 1 `order_outbox` row | Test |

### Deliverables

- `POST /api/v1/orders` returns `202 Accepted` within 50ms
- `400 Bad Request` returned when `Idempotency-Key` header is absent
- 5 retries with same key → exactly 1 order row in `orders_db`
- Kill process between DB write and response → retry returns the original response (Postgres fallback)
- `order_outbox` row with `published=false` exists after order creation

### Dependencies

- Week 1: `orders_db` with V1 migration applied
- Week 4: `ReservationId` typed value object as reference (ACL translation pattern)

### Definition of Done

```
[ ] POST /api/v1/orders returns 202 in < 50ms
[ ] Missing Idempotency-Key header returns 400 with structured error body
[ ] 5 retries with same key: 1 orders row, 1 outbox row, 5 identical 202 responses
[ ] Order + OutboxEvent write is atomic: crash test (kill -9 mid-transaction) leaves no partial state
[ ] IdempotencyRecord written to both Redis (TTL 24h) and Postgres simultaneously
[ ] IdempotencyKey.isExpired() boundary test: key at 23h59m59s vs 24h00m01s
```

### Interview question unlocked

> "Walk me through your transactional outbox. Why can't you publish directly to Kafka inside the DB transaction? What happens if Kafka is down for 5 minutes? How does the outbox poller guarantee at-least-once delivery?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| JPA `@Transactional` and Kafka publish in same method | High | This is the bug to prevent: the outbox pattern exists to solve this. Never call `KafkaTemplate.send()` inside `@Transactional` |
| Redis idempotency key evicted before Postgres is checked | Low | `idem:` keys use fixed 24h TTL; eviction is LRU — recently-used idempotency keys are last to be evicted |
| Concurrent requests with same key race to write | Medium | `UNIQUE (idempotency_key)` on `orders` table is the database-level guard; second writer gets a `DataIntegrityViolationException`, handled as idempotent hit |

---

## Week 6 — Kafka Wiring: Outbox Poller + Saga Consumer

**Phase:** Integration
**Goal:** Events flow end-to-end. A reservation triggers a `StockReserved` Kafka event. The OrderService saga consumer receives it and confirms the reservation. This is the highest-risk week — all three services must work together for the first time.

### Objectives

- Outbox poller running with `FOR UPDATE SKIP LOCKED` for concurrent-safe multi-pod operation
- All Kafka topics created with correct partition counts and keys
- `InventoryEventTranslator` ACL translates `StockReserved` → `PurchaseIntent`
- End-to-end integration test: reserve → Kafka → saga step → database confirmed

### Tasks

| # | Task | Area |
|---|---|---|
| 6.1 | Outbox poller: `@Scheduled` 500ms, `SELECT FOR UPDATE SKIP LOCKED LIMIT 100`, batch publish | Kafka |
| 6.2 | `KafkaTopicConfig`: `sale-events` (8p), `inventory-events` (16p), `order-events` (8p) | Kafka |
| 6.3 | `InventoryEventConsumer` in OrderService: routes `StockReserved` → `processReservationConfirmed()` | Kafka |
| 6.4 | `InventoryEventTranslator` ACL: maps `StockReservedPayload` → `PurchaseIntent` (no InventoryService type imports in OrderService) | ACL |
| 6.5 | `NotificationService` skeleton: `@KafkaListener` on all 3 topics, log events, `enable.auto.commit=false` | Service |
| 6.6 | All consumers: `enable.auto.commit=false`, manual `Acknowledgment.acknowledge()` after processing | Config |
| 6.7 | Integration test: POST /reservations → outbox poller fires → `StockReserved` on Kafka → OrderService consumes | Test |

### Deliverables

- Outbox poller processes 100 events/batch, marks `published=true` — verified via psql
- Kafka UI shows `inventory-events` with 16 partitions, `productId` as key
- `StockReserved` events land on consistent partitions for the same `productId`
- End-to-end test completes in < 2s: reservation → Kafka → saga step
- `InventoryEventTranslator` has zero imports from InventoryService packages

### Dependencies

- Week 3: `InventoryService` with Kafka producer capability
- Week 4: `Reservation` aggregate publishable
- Week 5: `OrderService` `OutboxEvent` entity and `@Transactional` write

### Definition of Done

```
[ ] Outbox poller: SELECT FOR UPDATE SKIP LOCKED verified by running 3 pods simultaneously — no duplicates
[ ] inventory-events topic has exactly 16 partitions in Kafka UI
[ ] InventoryEventTranslator has no imports from inventory.* package
[ ] NotificationService logs all 3 topic events with correct eventType
[ ] enable.auto.commit=false on all consumers — verified in application.yml
[ ] End-to-end integration test: reserve → Kafka event → confirmed reservation in < 2s
```

### Interview question unlocked

> "Why `FOR UPDATE SKIP LOCKED` on the outbox poller? What happens without `SKIP LOCKED` when 3 pods poll concurrently? Why is this better than a distributed lock like Redlock?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| ACL boundary violated (OrderService imports InventoryService types) | High | Enforce with ArchUnit test: no `com.flashsale.inventory.*` import in `com.flashsale.order.*` packages |
| Kafka producer and DB `@Transactional` accidentally coupled | High | Code review gate: `KafkaTemplate.send()` must never appear inside a `@Transactional` method |
| Consumer offset committed before processing completes | Medium | Review every `@KafkaListener` method — `ack.acknowledge()` must be last line, inside `try` success path only |

---

## Week 7 — SaleService: Redis Cache + Rate Limiter

**Phase:** Integration
**Goal:** `GET /api/v1/sales/{id}/active` is served from Redis with zero Postgres queries on the hot path. The rate limiter enforces 10 requests/minute per user. A load test proves the Redis path at 10k RPS.

### Objectives

- `sale:active:{saleId}` Redis key serves the hot path — Postgres never touched during active sale
- Sale activation scheduler fires within ±5 seconds of `saleStart`
- `rate_limit.lua` sliding window rate limiter with fail-open circuit breaker
- `sale:active` key deleted immediately on `SaleEnded` — not waiting for TTL

### Tasks

| # | Task | Area |
|---|---|---|
| 7.1 | `GET /api/v1/sales/{id}/active`: Redis `GET sale:active:{saleId}` → Postgres fallback on miss | API |
| 7.2 | `SaleActivationScheduler`: `@Scheduled` every 5s, find SCHEDULED + `sale_start <= NOW()`, transition ACTIVE | Scheduler |
| 7.3 | On ACTIVE transition: `SET sale:active:{saleId} 1 EX {ttl}`, publish `SaleStarted` to `sale-events` | Redis |
| 7.4 | `RateLimiterService`: `rate_limit.lua` execution + fail-open `CircuitBreaker` on `RedisConnectionFailureException` | Redis |
| 7.5 | `429 Too Many Requests` response with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` headers | API |
| 7.6 | On `SaleEnded`: `DEL sale:active:{saleId}` immediately + update `sale:meta:{saleId}` hash status field | Redis |
| 7.7 | Load test: 10k RPS against `GET /active` — verify 0 Postgres queries via `pg_stat_statements` | Test |

### Deliverables

- `GET /api/v1/sales/{id}/active` returns in < 5ms p99 when Redis is warm
- Sale activates within ±5 seconds of `saleStart` — verified by watching `sale_status_history`
- 11th request/minute returns `429` with correct `Retry-After` value
- Load test: 10k RPS, `pg_stat_statements` shows 0 queries on `flash_sales` table
- Kill Redis during load test: requests fall back to Postgres, no 500 errors

### Dependencies

- Week 2: `SaleService` domain model and Kafka producer
- Week 3: `stock_prewarm.lua` to trigger from activation scheduler
- Week 6: `SaleStarted` Kafka event infrastructure

### Definition of Done

```
[ ] GET /active: 0 Postgres queries when sale:active key exists (pg_stat_statements)
[ ] Activation fires within 5 seconds of saleStart — log timestamp comparison
[ ] Rate limiter: 10 requests allowed, 11th returns 429 with Retry-After header
[ ] Fail-open: stop Redis, send 11 requests — all 11 are allowed (circuit breaker open)
[ ] DEL sale:active on SaleEnded: no reservation accepted after sale ends
[ ] Load test: 10k RPS, Redis p99 < 1ms, 0 Postgres queries
```

### Interview question unlocked

> "How do you ensure `GET /active` never hits Postgres during an active sale? What is your pre-warm strategy and what happens if pre-warm fails? How does the fail-open rate limiter affect your security posture during a Redis outage?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Clock skew between SaleService pods causing double activation | Medium | `UPDATE flash_sales SET status='ACTIVE' WHERE status='SCHEDULED' AND version=$expected` — optimistic lock prevents double transition |
| Rate limiter Sorted Set growing unbounded if PEXPIRE not reset | Low | `PEXPIRE` called on every `ZADD` — verify with TTL check after 100 requests with no new requests |
| Redis pre-warm race condition (two pods both call prewarm) | Low | `stock_prewarm.lua` NX guard on `stock:warmed:{saleId}` makes it idempotent |

---

## Week 8 — Retry, DLQ, and NotificationService

**Phase:** Resilience
**Goal:** Every failure mode has a defined path — retriable errors retry with backoff, terminal errors go to DLQ immediately, and the DLQ is replayable. A chaos test proves zero event loss when Kafka is killed and restarted.

### Objectives

- `RetryPublisher` and `DlqPublisher` classify errors and route accordingly
- Retry consumers implement exponential backoff via virtual thread sleep
- `NotificationService` dispatches stubs for email/push/SMS with `eventId` deduplication
- Chaos test: kill Kafka mid-sale, restart, verify zero lost events and zero duplicates

### Tasks

| # | Task | Area |
|---|---|---|
| 8.1 | `RetryPublisher`: classify `RETRIABLE` vs `TERMINAL`, wrap in `RetryMessage` envelope with metadata | Retry |
| 8.2 | `DlqPublisher`: write to `notifications.dlq` with full `dlqMetadata` + `originalEvent` preserved intact | DLQ |
| 8.3 | Retry consumers on `*.retry` topics: check `nextAttemptAfter`, virtual thread `Thread.sleep()` for delay | Retry |
| 8.4 | `NotificationService`: `OrderCreated` → email stub, `SaleStarted` → waitlist stub, `ReservationExpired` → buyer stub | Service |
| 8.5 | `eventId` deduplication in NotificationService: in-memory `Set<String>` with 1h TTL (Caffeine cache) | Service |
| 8.6 | DLQ replay tool: `POST /admin/dlq/replay` reads `notifications.dlq`, re-publishes `originalEvent` to `originalTopic` | Ops |
| 8.7 | Chaos test: `docker stop kafka`, wait 2 minutes, `docker start kafka`, verify all events processed | Test |

### Deliverables

- `DeserializationException` → DLQ immediately (no retry)
- `RedisConnectionFailureException` → 3 retries at 1s/8s/32s → DLQ
- DLQ replay re-publishes `originalEvent` preserving `eventId` — consumers deduplicate correctly
- `NotificationService` logs dispatch for each event type — no actual external calls yet
- Chaos test: 2-minute Kafka outage → 0 lost events, 0 duplicate orders on recovery

### Dependencies

- Week 6: Kafka consumers and producers for all three services

### Definition of Done

```
[ ] TERMINAL errors: DeserializationException, UnknownEventTypeException → DLQ on first attempt
[ ] RETRIABLE errors: retry 3x (sale/order) or 5x (inventory) with correct backoff intervals
[ ] notifications.dlq contains full originalEvent — verified by replaying and confirming processing
[ ] eventId deduplication: same event consumed twice → 1 dispatch, not 2
[ ] DLQ depth alert test: manually publish 101 messages → alert fires
[ ] Chaos test: kafka restart → all outbox messages processed, consumer lag returns to 0
```

### Interview question unlocked

> "How do you classify a `RETRIABLE` error vs a `TERMINAL` error? What happens if you retry a deserialization failure? Why does the retry consumer use `Thread.sleep()` instead of a scheduled delay? Why is this safe with Java 21 virtual threads?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Retry loop consuming all consumer threads on sustained failures | Medium | Max retry attempts enforced; after exhaustion, event goes to DLQ and offset is committed |
| DLQ replay re-processing events that were already successfully processed | Medium | Consumers check `eventId` against deduplication cache before processing replayed events |
| Notification deduplication cache lost on pod restart | Low | Acceptable: NotificationService is eventually consistent. A duplicate email on pod restart is a UX issue, not a data integrity issue |

---

## Week 9 — AnalyticsService + Observability

**Phase:** Observability
**Goal:** Every Kafka event lands in ClickHouse within 5 seconds. Every service exposes Prometheus metrics. Distributed traces propagate `traceId` across Kafka headers and HTTP calls.

### Objectives

- `AnalyticsService` batch-writes all Kafka events to ClickHouse `sale_events` table
- Micrometer metrics on all services: reservation latency, consumer lag, Redis latency
- OpenTelemetry `traceId` in every log line and Kafka message header
- Alerting rules configured for all NFR-027 thresholds

### Tasks

| # | Task | Area |
|---|---|---|
| 9.1 | `AnalyticsService`: Kafka consumer → ClickHouse JDBC writer, flush every 1000 events or 1 second | Service |
| 9.2 | `SaleEventProjection`: map all 10 event types to `sale_events` wide table columns | Domain |
| 9.3 | Micrometer `Timer` on `POST /reservations`: measures p50/p95/p99 latency | Metrics |
| 9.4 | Micrometer `Gauge` on Kafka consumer lag per consumer group | Metrics |
| 9.5 | OpenTelemetry: `traceId` set in `MDC` on request entry, propagated in Kafka `RecordHeader` | Tracing |
| 9.6 | `/actuator/prometheus` endpoint on all 5 services | Ops |
| 9.7 | Alerting rules: p99 > 100ms / 250ms, consumer lag > 5k / 10k, Redis memory > 70% / 85% | Ops |

### Deliverables

- ClickHouse `sale_events` table populated within 5 seconds of Kafka publish
- `curl localhost:8081/actuator/prometheus` shows `reservation_latency_seconds` histogram
- Every log line contains `traceId=<uuid>` — verified with `grep traceId` across all service logs
- Single reservation traced end-to-end from HTTP request through Kafka to order creation
- Alerting rules fire correctly: simulate lag > 10k → alert triggers

### Dependencies

- Week 6: all Kafka topics publishing events
- Weeks 2–5: all services must be running to produce metrics

### Definition of Done

```
[ ] ClickHouse sale_events populated within 5s: SELECT COUNT(*) after sending test event
[ ] /actuator/prometheus returns reservation_latency_seconds histogram with quantiles
[ ] traceId present in all log lines for a single reservation flow
[ ] traceId propagated in Kafka RecordHeader — verified by consuming a raw record and printing headers
[ ] Kafka consumer lag metric visible in Prometheus
[ ] All 5 alerting thresholds verified by deliberately exceeding each one
```

### Interview question unlocked

> "How do you propagate `traceId` across a Kafka message boundary? Where does it live in the Kafka record? How do you correlate logs across 5 services for a single user reservation? What's the difference between a trace and a metric?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| ClickHouse JDBC driver compatibility with Spring Boot 3 | Medium | Test ClickHouse JDBC driver version against Spring Boot 3's JDBC abstraction early in the week |
| TraceId lost at Kafka boundary (not added to headers) | Medium | Integration test: publish one event, consume it, assert `X-Trace-Id` header exists with correct UUID |
| Consumer lag metric requires Kafka AdminClient | Low | Spring Kafka's `KafkaAdmin` bean exposes consumer group offsets — wrap in a `@Scheduled` gauge update |

---

## Week 10 — Kubernetes + Load Test + Interview Preparation

**Phase:** Production Readiness
**Goal:** All 5 services deploy to Kubernetes with correct HPA config. A Gatling load test at 50,000 concurrent users passes with p99 < 50ms and zero oversells. Every architectural decision can be explained fluently.

### Objectives

- Helm charts for all 5 services with correct HPA min/max replicas per spec
- Gatling load test: 50k concurrent users, 1000 stock units, zero oversells, zero duplicate orders
- Pre-sale runbook executed and validated
- Every ADR can be explained in 60 seconds with tradeoffs articulated

### Tasks

| # | Task | Area |
|---|---|---|
| 10.1 | Helm charts for all 5 services: `Deployment`, `Service`, `HorizontalPodAutoscaler`, `ConfigMap`, `Secret` | K8s |
| 10.2 | HPA: CPU 70% trigger, min/max replicas per spec (InventoryService: 2–10, OrderService: 2–10, etc.) | K8s |
| 10.3 | `ConfigMap` for non-sensitive config; `Secret` for DB passwords, Redis auth, Kafka credentials | K8s |
| 10.4 | Liveness `/actuator/health/liveness` + readiness `/actuator/health/readiness` probes on all services | K8s |
| 10.5 | Gatling simulation: 50k users ramp over 60s, each reserving 1 unit from 1000-unit sale | Test |
| 10.6 | Post-sale reconciliation job: `SELECT COUNT(*) FROM orders WHERE status='CONFIRMED'` = Redis final stock delta | Ops |
| 10.7 | Interview dry-run: answer all 15 ADRs, all 10 acceptance criteria, all 5 "what if Redis is down" variants | Prep |

### Deliverables

- `kubectl apply -f helm/` → all 5 services `Running` in Kubernetes within 2 minutes
- Gatling report: p99 < 50ms, 0 oversells (confirmed orders ≤ 1000), 0 duplicate orders
- HPA scales InventoryService from 2 → 10 pods under Gatling load, back to 2 after
- Reconciliation job confirms Redis final stock = `totalStock - confirmedOrders` (zero drift)
- ADR dry-run: all 15 decisions explained correctly with alternatives and tradeoffs stated

### Dependencies

- Weeks 1–9: everything must be working before Kubernetes adds operational complexity

### Definition of Done

```
[ ] kubectl get pods shows all 5 services in Running state
[ ] All liveness and readiness probes pass — no pod restart loops
[ ] Gatling p99 reservation latency < 50ms at 50k concurrent users
[ ] Confirmed orders == initial stock units (1000) — zero oversells
[ ] Zero duplicate orders — verified by SELECT COUNT(*) / GROUP BY idempotency_key
[ ] HPA scale-up and scale-down observed during and after load test
[ ] Reconciliation job: drift = 0 between Redis stock delta and Postgres confirmed orders
[ ] Can explain every ADR in 60 seconds without notes
```

### Interview question unlocked

> "Your load test passed at 50k RPS. Walk me through exactly what happens at T+0 when 50,000 users hit Buy simultaneously. What is the first thing that could go wrong? What is the second? At what scale does your current design break and what would you change?"

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Kubernetes HPA not scaling fast enough at sale start (60–90s lag) | High | Pre-scale InventoryService to max replicas 5 minutes before saleStart — documented in pre-sale runbook |
| Gatling generating more load than local machine can sustain | Medium | Run Gatling on a separate machine or EC2 instance; local machine resource contention skews results |
| Post-sale reconciliation reveals drift between Redis and Postgres | Low | Root-cause before calling the project done. Expected causes: missed `stock_release.lua` call or replay of `ReservationReleased` event |

---

## Appendix A — Phase summary

| Phase | Weeks | Services touched | Primary risk |
|---|---|---|---|
| Foundation | 1 | Infrastructure | Docker/Redis Cluster config complexity |
| Core Services | 2–5 | SaleService, InventoryService, OrderService | Lua script atomicity, Outbox pattern |
| Integration | 6–7 | All 3 transactional services + API layer | ACL boundary violations, Kafka offset management |
| Resilience | 8 | NotificationService | Error classification, DLQ replay safety |
| Observability | 9 | All 5 services | Trace propagation across Kafka boundaries |
| Production Readiness | 10 | All 5 services | HPA lag at sale start |

## Appendix B — Interview questions by ADR

| ADR | Question | Unlocked week |
|---|---|---|
| ADR-001 (Lua DECR) | Why Lua over WATCH/MULTI/EXEC? | Week 3 |
| ADR-002 (Virtual Threads) | Why not WebFlux? What does Loom give you? | Week 2 |
| ADR-003 (Kafka async) | Why not HTTP between services? What does temporal decoupling buy you? | Week 6 |
| ADR-004 (Outbox) | What happens if Kafka is down for 5 minutes? | Week 5 |
| ADR-006 (Partition key) | Why productId for inventory-events? What breaks with saleId? | Week 6 |
| ADR-008 (DB per service) | Why not one Postgres with three schemas? What is the operational difference? | Week 1 |
| ADR-009 (5 services) | You started with 3 services. Was the original design wrong? | Week 2 |
| ADR-010 (SaleService split) | Why is flash sale lifecycle not an inventory concern? | Week 7 |
| ADR-012 (Choreography) | Why choreography over orchestration? What visibility do you lose? | Week 6 |
| ADR-013 (productId key) | Walk me through the race condition that productId key prevents. | Week 6 |

## Appendix C — Acceptance criteria tracking

| AC | Requirement | Target | Verified week |
|---|---|---|---|
| AC-001 | Reservation P99 latency | ≤ 50ms at 50k users | Week 10 |
| AC-002 | Oversell rate | 0 under all conditions | Week 4 (unit) + Week 10 (load) |
| AC-003 | Order idempotency | 0 duplicate orders on retry | Week 5 |
| AC-004 | Event delivery | At-least-once, 0 lost on Kafka restart | Week 8 |
| AC-005 | Sale start accuracy | Transition within ±5 seconds | Week 7 |
| AC-006 | Notification latency | ≤ 30 seconds post-OrderCreated | Week 8 |
| AC-007 | Analytics lag | ≤ 5 seconds | Week 9 |
| AC-008 | Redis fallback correctness | 0 oversell when Redis down | Week 3 |
| AC-009 | Saga compensation | Stock restored after PaymentFailed within 30s | Week 6 |
| AC-010 | Rate limiting | 429 on > 10 req/min, no reservation leaks | Week 7 |

---

*Build plan derived from Final-Spec-Council.md v2.0 and all subsequent design documents.*
*Each week is independently deliverable. No week depends on work not yet completed.*
*Risks are identified per week — do not defer risk discovery to Week 10.*