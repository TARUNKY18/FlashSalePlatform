# CURRENT_STATE.md
**Milestone:** Week 3 — InventoryService
**Status:** 🟡 IN PROGRESS
**Date:** 2026-08-05
**Engineer:** Tarun K Y

---

## Current Status

| Item | Verified state |
|---|---|
| Branch | `main` |
| Latest commit | `614d2cd` — `docs(adr): revise ADR-020 after architecture review adjudication` |
| Implementation HEAD | `f12d67d` — `feat(inventory): add jqwik property-based stock correctness tests` |
| Implementation commit status | Pushed; local `main` matches `origin/main` |
| Build | Whole-project `BUILD SUCCESSFUL` in 59s |
| Production Java files | 30 |
| Test classes | 18 |
| Inventory tests | 131 passed, 0 failed, 0 errors, 0 skipped |
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

---

## Verification

```text
./gradlew :services:inventory-service:test --tests 'com.flashsale.inventory.domain.aggregate.ProductStockCorrectnessPropertyTest'
BUILD SUCCESSFUL in 23s
5 properties / 5,000 generated examples passed

./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL in 30s
131 tests passed, 0 failed, 0 errors, 0 skipped

./gradlew clean build
BUILD SUCCESSFUL in 59s
Inventory: 131 passed; SaleService: 16 passed; 0 failed/errors/skipped
```

Inventory verification comprises 110 unit/property tests and 21 real
PostgreSQL/Redis Testcontainers tests. jqwik is present only on the Inventory
test runtime classpath and is absent from its production runtime classpath.

---

## Database

`inventory_db` contains only `products` and Product-owned `stock_levels`.
`(product_id, sale_id)` is unique. No Reservation, release, reconciliation,
audit, outbox, Kafka, or Week 4 table exists.

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
- No Kafka, Inventory REST API, Reservation, release, or reconciliation is in scope.

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
- Future pre-warm/reconciliation must write compatible revision keys.
- Standalone Redis Testcontainers coverage does not prove Redis Cluster
  topology behavior.
- A committed response lost before the client receives it can be retried and
  decrement again; cross-request idempotency remains out of scope.
- The SaleService migration defect remains outside this slice.
- `PROJECT_TRUTH.md` and `REPOSITORY_INDEX.md` remain stale.

---

## Remaining Week 3 Work

- ✔ Pre-warm Architecture — ADR-020 Revision 2 approved (design complete)
- ➡ Pre-warm Use Case implementation (against approved ADR-020 Revision 2 contract)
- ➡ Regression maintenance for any subsequently approved Week 3 slice
- ➡ Documentation reconciliation

---

## Next Recommended Task

**Pre-warm Use Case implementation:** implement the approved ADR-020 Revision 2
contract — trigger/source event consumption, pre-start timing validation,
authoritative PostgreSQL snapshot load, revision-compatible atomic Lua
initialization, and TTL derivation from `saleEnd + 10 minutes`. Preserve
the 131-test baseline. Do not add REST, Kafka, Reservation, release,
reconciliation, or retry logic without separate approval.
