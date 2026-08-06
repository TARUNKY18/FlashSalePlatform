# CURRENT_STATE.md
**Milestone:** Week 4 — Reservation (in progress)
**Status:** 🟡 IN PROGRESS
**Date:** 2026-08-06
**Engineer:** Tarun K Y

---

## Current Status

| Item | Verified state |
|---|---|
| Branch | `main` |
| Latest commit | `713d2d2` — `feat(inventory): implement reservation domain aggregate` |
| Implementation commit status | All Week 3 and Week 4 Slice 1 committed. Working tree: clean. HEAD: `713d2d2` |
| Build | `BUILD SUCCESSFUL` in 47s |
| Production Java files | 50 |
| Test classes | 28 |
| Inventory tests | 210 passed, 0 failed, 0 errors, 0 skipped |
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

---

## Verification

```text
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL in 47s
210 tests passed, 0 failed, 0 errors, 0 skipped
```

Inventory verification comprises 182 unit/property tests and 28 real
PostgreSQL/Redis Testcontainers tests. jqwik is present only on the Inventory
test runtime classpath and is absent from its production runtime classpath.

---

## Database

`inventory_db` contains only `products` and Product-owned `stock_levels`.
`(product_id, sale_id)` is unique. The Reservation domain aggregate exists in
the domain layer only; no `reservations` table or Flyway migration has been
added yet. No release, reconciliation, audit, outbox, or Kafka table exists.

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
- Reservation persistence, REST API, Redis duplicate guard, expiry sweep, and Kafka events are not yet in scope.

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
- Pre-warm use case is implemented but not yet committed; working tree must be
  committed before proceeding to documentation reconciliation.
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

Week 4 is **IN PROGRESS**. Slice 1 (Reservation domain aggregate) committed at `713d2d2` and documentation reconciled in SESSION-011.

Remaining slices:
- Reservation persistence (JPA entity, mapper, V2 Flyway migration for `reservations` + `stock_reservation_log`)
- REST `POST /api/v1/reservations` controller
- Redis `resv:lock:{userId}:{saleId}` NX duplicate guard
- `stock_release.lua` integration and `StockReleasePort`
- Expiry sweep `@Scheduled`
- `StockReserved` / `ReservationExpired` Kafka events
- 1500-concurrent integration test (1000-unit sale)
- `ReservationCommandService` application service
