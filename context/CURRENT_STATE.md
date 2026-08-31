# CURRENT_STATE.md
**Milestone:** Week 5 — OrderService (complete through Slice 2 / Build Plan task 5.2)
**Status:** 🟢 COMPLETE
**Date:** 2026-08-28
**Engineer:** Tarun K Y

---

## Current Status

| Item | Verified state |
|---|---|
| Branch | `main` |
| Latest commit | `86a9c5cbef10fe5e73abab97a9b69413280f3187` — `docs: update task 5.2 documentation` |
| Implementation commit status | Week 5 Slice 2 implementation is committed at `df6d98ff1b9620a31a03cfdf0d0427aab922d964`; local `main` and `origin/main` both resolve to `86a9c5cbef10fe5e73abab97a9b69413280f3187` |
| Build | Focused OrderService tests passed; whole-project regression was blocked by local Docker/Testcontainers client initialization |
| Production Java files | 107 total: InventoryService 70, SaleService 30, OrderService 7 |
| Test classes | 44 total; Week 5 Slice 2 added `OrderTest` |
| OrderService tests | 11 passed, 0 failed, 0 errors, 0 skipped |
| Inventory verification | 259 non-container tests passed; 60 Testcontainers tests could not initialize Docker, so the full 319-test baseline was not re-confirmed |
| SaleService regression | 16 passed, 0 failed, 0 errors, 0 skipped |

---

## Completed Work

| Slice | Current implementation |
|---|---|
| ✔ InventoryService Skeleton | Gradle module; Java 21; Spring Boot 3.3.4; virtual threads; PostgreSQL, JPA, Flyway, Redis Cluster, and Actuator configuration |
| ✔ Inventory Domain | Framework-free Product aggregate, Product-owned StockLevel, StockCount, ProductId, SaleId, and StockLevelId |
| ✔ Inventory Persistence | Separate JPA entities, isolated mapper, ProductRepository application port and JPA adapter, entity-graph loading, optimistic versions |
| ✔ Inventory Flyway Migration | `products` and `stock_levels` with PK, FK, stock/version checks, and unique Product + Sale allocation |
| ✔ Redis Lua Integration | Approved `stock-decrement.lua`, singleton typed script bean, SHA caching, and Lua executor |
| ✔ Redis Adapter | Redis-neutral `StockDecrementPort`; raw result preservation; connection loss, timeout, and indeterminate transport failures translated; deterministic failures remain fail-closed |
| ✔ Redis Re-warming | Completed at `10069d8`; its `SETNX` implementation was subsequently replaced by the approved revision-fenced projection synchronization gate |
| ✔ Durable PostgreSQL Authority | `DurableStockDecrementPort`; separately proxied `TransactionalStockDecrement`; Product-root `PESSIMISTIC_WRITE`; commit-before-return; StockLevel revision returned with authoritative stock |
| ✔ Canonical StockCounterService | Product validation; one Redis attempt; one durable invocation for every recognized/indeterminate Redis outcome; PostgreSQL-derived result; warning on disagreement; post-commit synchronization |
| ✔ Revision-fenced Redis Projection | `StockProjectionSyncPort`; stock/version hash-tagged keys; equal/newer apply, older ignore, revisionless invalidation, TTL preservation and mirroring |
| ✔ Infrastructure Correctness Tests | Real PostgreSQL and Redis Testcontainers coverage for Flyway/Hibernate, commit failure, concurrency, ambiguous Lua execution, disagreement, fencing, missing keys, and TTL behavior |
| ✔ Property-Based Stock Correctness Tests | Test-scoped jqwik 1.9.0; five properties with 1,000 generated examples each cover exact decrement, non-negative stock, insufficient-stock non-mutation, exact depletion, repeated operations, boundaries, and overflow-safe input ranges |
| ✔ ADR-020 Pre-Warm Architecture (Revision 2) | Six architecture-review findings adjudicated and resolved; governing architecture approved; no production or test code changed |
| ✔ Pre-Warm Use Case (SESSION-009, committed `7b68f14`) | `PreWarmStockUseCase`, `StockPreWarmPort`, `RedisStockPreWarmAdapter`, `StockPreWarmLuaExecutor`, revision-fenced `stock-prewarm.lua`; `InventoryConfiguration` (`Clock` bean); `PreWarmStockResult` enum; 32 tests (+4 classes); reviewed and approved; committed |
| ✔ Reservation Domain Aggregate (SESSION-011, committed `713d2d2`) | `Reservation` aggregate root (sealed `Status`, `create`/`reconstitute`, `confirm`/`expire`/`release`); `ReservationId`, `UserId`, `OrderId`, `Quantity`, `ReservationExpiry` value objects; `ReservationTest` (29 cases), `ReservationValueObjectTest` (18 cases); 47 new tests; reviewed and approved; committed |
| ✔ Reservation Persistence (SESSION-012, committed `683efe4`) | V2 Flyway migration (`reservations`, `stock_reservation_log`); `ReservationJpaEntity` (`@Version`, immutable fields, `updateStatus`/`updateOrderId`); `ReservationPersistenceMapper`; `SpringDataReservationRepository`; `ReservationRepository` application port and infra adapter (load-then-update pattern for `@Version` correctness); `ReservationPersistenceMapperTest`, `ReservationRepositoryAdapterTest` (6 cases), `ReservationPersistenceIntegrationTest` (8 cases); adversarial review: F-1 applied, F-2 rejected (optimization-only), F-3 applied; 31 new tests; reviewed and approved; committed |
| ✔ REST + Command Service (SESSION-013, committed `0b4c1c4`) | V3 Flyway migration (idempotency_key NOT NULL); `Reservation.idempotencyKey` field; `ReservationJpaEntity`/`ReservationPersistenceMapper`/`ReservationRepository` updated for idempotencyKey; `ReservationDuplicateGuardPort`/`ReservationDuplicateGuardUnavailableException`; `RedisReservationDuplicateGuardAdapter` (SET NX EX 30s, `resv:lock:{userId}:{saleId}`); `CreateReservationCommand`, `ReservationCreatedResult` (sealed); `ReservationCommandService` (idempotency→Redis guard→stock decrement→persist); `ReservationController` (POST /api/v1/reservations, 201/200/409/400); `InventoryExceptionHandler`; 3 DTO records; `spring-boot-starter-validation` added; 27 new tests; BUILD SUCCESSFUL 268/268 |
| ✔ stock_release.lua integration + expiry sweep (SESSION-014, committed `5810c7d`) | `stock-release.lua` KEEPTTL fix (approved CONFLICT-NEW-001); `StockReleasePort`, `StockReleaseUnavailableException`, `StockReleaseResult`; `StockReleaseLuaExecutor`, `RedisStockReleaseAdapter`; `stockReleaseScript` bean; `@EnableScheduling` on `InventoryConfiguration`; `findExpiredPending(Instant)` on `ReservationRepository` application port, Spring Data query, and infra adapter; `ReservationExpiryService` (`@Scheduled(fixedDelay=30_000)`); 26 new tests (unit + integration); BUILD SUCCESSFUL 294/294 Inventory, 310/310 total |
| ✔ Inventory transactional outbox + Kafka events (SESSION-015, committed `233ca84`) | V4 `inventory_outbox`; infrastructure-only JPA persistence; atomic reservation/expiry + outbox writes; `StockReserved` and `ReservationExpired`; PostgreSQL-authoritative `remainingStock`; post-commit Redis restoration; 500 ms `FOR UPDATE SKIP LOCKED` publisher (100 rows); stable `eventId` at-least-once delivery; `productId` key; Inventory-owned `inventory-events` topic; unsupported persisted event types rejected before any send; real same-endpoint Kafka outage/recovery coverage; 24 new tests; frozen contract PASS |
| ✔ Reservation Concurrency Integration Test (SESSION-016, committed `8a60df7`) | Test-only `ReservationConcurrencyIntegrationTest`; 1500 concurrent MockMvc POST requests for one 1000-unit product/sale, with unique users and idempotency keys and quantity 1; exactly 1000 `201` and 500 `409 SOLD_OUT`; durable PostgreSQL stock 0/revision 1000/Product revision 0; 1000 distinct PENDING reservations and linked `StockReserved` outbox rows; Redis stock 0/version 1000; all 28 frozen contract requirements passed; no production behavior changed |
| ✔ OrderService Spring Boot Bootstrap (SESSION-017, committed `567450e`) | Week 5 Slice 1 / task 5.1; minimal Spring Boot 3.3.4 Web/Actuator module; inherited Java 21; virtual threads; `order-service` application name; port 8083 with `ORDER_SERVICE_PORT` override; no domain, API, persistence, messaging, or tests |
| ✔ Order Aggregate (SESSION-018, committed `df6d98f`) | Week 5 Slice 2 / task 5.2; framework-free in-memory `Order`; `place(...)` creates version-0 `PENDING`; Order-owned `PurchaseIntentId`; typed `OrderId`/`UserId`/`SaleId`; positive-only `Money`; opaque nonblank string idempotency key; explicit `Instant` creation/transition times; manual `confirm`/`cancel`/`expire`; terminal-state enforcement; 11 focused tests |

---

## Verification

```text
OrderService: 11 passed, 0 failed, 0 errors, 0 skipped
SaleService:   16 passed, 0 failed, 0 errors, 0 skipped
Inventory non-container suites: 259 passed, 0 failed, 0 errors, 0 skipped
Whole-project clean build: BLOCKED — Docker/Testcontainers client initialization
```

The full historical baseline remains 319 Inventory tests plus 16 SaleService
tests (335 total), but it was not re-confirmed for Slice 2. Both full-build
attempts reached the Inventory suite and reported 60 container-dependent
failures caused by Docker client initialization; no Task 5.2 assertion failed.

OrderService smoke verification passed: it starts without external
infrastructure, binds to port 8083 by default, honors `ORDER_SERVICE_PORT`,
returns HTTP 200 with `UP` at `/actuator/health`, and returns HTTP 404 for
`/api/v1/orders`. Spring virtual threads are enabled in effective configuration.

Inventory verification comprises unit, property, and integration tests.
jqwik is present only on the Inventory test runtime classpath and is absent
from its production runtime classpath.

---

## Database

`inventory_db` contains `products`, Product-owned `stock_levels`,
`reservations`, and `stock_reservation_log`. V1 created `products` and
`stock_levels`; V2 added `reservations` (partial unique index on
`(user_id, sale_id)` where `status IN ('PENDING','CONFIRMED')`) and
`stock_reservation_log` (FK to `products`). V3 enforces `idempotency_key NOT NULL`.
V4 adds infrastructure-owned `inventory_outbox`, with a restrictive FK to
`reservations`, unique stable `event_id`, JSONB payload, publish state, and retry
metadata. No release, reconciliation, or additional Kafka table exists.

---

## Architecture Locked

- SaleService retains `SCHEDULED → ACTIVE → ENDED → ARCHIVED`.
- Hexagonal boundaries: domain, application/ports, infrastructure.
- Product exclusively owns StockLevels; no independent StockLevel repository.
- Domain remains free of Spring, JPA, Hibernate, Redis, and Kafka.
- Typed IDs remain at domain/application boundaries; SaleId stays opaque.
- JPA entities remain separate; ProductPersistenceMapper owns translation.
- Flyway owns schema changes; Hibernate remains `ddl-auto: validate`.
- Product and StockLevel retain optimistic version mapping.
- PostgreSQL `stock_levels.current_stock` is authoritative for every returned
  decrement outcome; Redis is an atomic admission/projection mechanism.
- Redis decrement remains one atomic Lua attempt using `stock:{saleId}`.
- Lua results remain `-2` cache miss, `-1` sold out, or non-negative stock.
- Executors remain business-decision-free; the Redis decrement adapter
  translates only indeterminate transport failures into the durable path.
- Application services depend on ports and access StockLevel through Product.
- `TransactionalStockDecrement` owns the Product-root lock, mapping, domain
  mutation, managed update, flush, and revision retrieval transaction.
- Commit completes before `DurableStockDecrementPort` returns.
- Post-commit synchronization uses `stock:{saleId}` and
  `stock:version:{saleId}` through `StockProjectionSyncPort`.
- Strictly older revisions are ignored; equal/newer revisions reapply the
  authoritative state without changing the stock key's expiry.
- Missing stock remains missing; revisionless stock is atomically invalidated.
- Synchronization failure is warned and cannot conceal the committed result.
- The Reservation domain aggregate is implemented (domain layer only).
- Reservation persistence is implemented (V2 Flyway migration, JPA entity, mapper, adapter).
- Reservation REST API and Redis duplicate guard implemented (Slice 3, `0b4c1c4`).
- Expiry sweep (`ReservationExpiryService`) and `stock-release.lua` integration implemented (Slice 4, `5810c7d`).
- Inventory transactional outbox and Kafka publication are implemented (Slice 5,
  `233ca84`) without adding outbox state to the Inventory domain.
- `StockReserved` is inserted atomically with a successful reservation;
  `ReservationExpired` is inserted atomically with the expiry transition.
- Redis restoration remains post-commit and does not alter the committed expiry event.
- Outbox publication is at-least-once: a crash after Kafka acknowledgement but
  before the database mark commits may redeliver the same stable `eventId`.
- Only `StockReserved` and `ReservationExpired` persisted event types are
  publishable; unsupported, null, blank, or unknown values fail the whole batch
  before the first Kafka send.
- OrderService contains the Week 5 Slice 1 bootstrap and the framework-free
  Week 5 Slice 2 `Order` aggregate. It still has no API, persistence, JPA,
  Flyway, PostgreSQL access, Redis, Kafka, idempotency infrastructure, outbox,
  scheduler, DTO, repository, or cross-service behavior.

---

## Important Invariants

- SaleService status transitions cannot skip or reverse states.
- StockCount, Product stock, and current stock are never negative.
- Allocation is positive and total allocations never exceed Product stock.
- At most one StockLevel exists per Product + Sale.
- Every StockLevel belongs to its Product and has
  `currentStock <= totalAllocated`.
- Generated arithmetic-model checks confirm successful decrements are exact,
  stock never becomes negative, and insufficient requests do not mutate stock.
- Product and StockLevel versions are never negative.
- Failed allocations do not mutate Product or increment its version.
- Redis decrement is never replaced by a client-side read/check/write sequence.
- Every recognized Redis result and every indeterminate transport failure
  invokes the durable port exactly once.
- PostgreSQL exclusively determines `Decremented` or `SoldOut`.
- Every successful durable mutation advances only the StockLevel version.
- Redis synchronization occurs only after durable completion and never retries.
- A missing stock key is never recreated by request-time synchronization.

---

## Known Risks

- StockCounterService currently loads Product before every Redis decrement.
- A PostgreSQL transaction and Product-root lock on every potentially
  successful request increase latency and contention.
- Product-root locking serializes different sales for the same Product.
- Missing or revisionless Redis stock remains unavailable until pre-warm;
  independent revision-key eviction can increase PostgreSQL load.
- Standalone Redis Testcontainers coverage does not prove Redis Cluster
  topology behavior.
- A committed response lost before the client receives it can be retried and
  decrement again; cross-request idempotency remains out of scope.
- The SaleService migration defect remains outside this slice.
---

## Week 3 Status

Week 3 is **COMPLETE**. All implementation slices committed and pushed to `origin/main`. Documentation reconciled in SESSION-010.

---

## Week 4 Status

Week 4 is **COMPLETE**. Slices 1–6 are complete and contract-verified. Slice 6
completed Build Plan task 4.7 as a test-only change committed at `8a60df7`.

---

## Week 5 Status

Week 5 Slice 1 / Build Plan task 5.1 is **COMPLETE** at `567450e`.

Week 5 Slice 2 / Build Plan task 5.2 is **COMPLETE** at `df6d98f`.

**Next sequential task:** Week 5 / Build Plan task 5.3 — `IdempotencyRecord`
and dual-layer idempotency. Its contract must resolve `CONFLICT-002` before
implementation.
