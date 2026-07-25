# CURRENT_STATE.md
**Milestone:** Week 3 — InventoryService
**Status:** 🟡 IN PROGRESS
**Date:** 2026-07-25
**Engineer:** Tarun K Y

---

## Current Status

| Item | Verified state |
|---|---|
| Branch | `main` |
| Latest commit | `10069d8` — `feat(inventory): add Redis re-warming after PostgreSQL fallback` |
| Build | `BUILD SUCCESSFUL` in 25s |
| Production types | 25 |
| Test classes | 12 |
| Tests | 71 passed, 0 failed, 0 skipped |

---

## Completed Work

| Slice | Current implementation |
|---|---|
| ✔ InventoryService Skeleton | Gradle module; Java 21; Spring Boot 3.3.4; virtual threads; PostgreSQL, JPA, Flyway, Redis Cluster, and Actuator configuration |
| ✔ Inventory Domain | Framework-free Product aggregate, Product-owned StockLevel, StockCount, ProductId, SaleId, and StockLevelId |
| ✔ Inventory Persistence | Separate JPA entities, isolated mapper, ProductRepository application port and JPA adapter, entity-graph loading, optimistic versions |
| ✔ Inventory Flyway Migration | `products` and `stock_levels` with PK, FK, stock/version checks, and unique Product + Sale allocation |
| ✔ Redis Lua Integration | Approved `stock-decrement.lua`, singleton typed script bean, SHA caching, and Lua executor |
| ✔ Redis Adapter | Redis-neutral StockDecrementPort; raw result preservation; Redis connection failures translated to an infrastructure-neutral unavailable signal |
| ✔ StockCounterService | Product-owned allocation validation; Redis success/sold-out mapping; one fallback call on cache miss or primary-counter unavailability |
| ✔ PostgreSQL Fallback | Product-owned domain decrement; StockFallbackPort; transactional Postgres adapter; Product-root `PESSIMISTIC_WRITE`; managed StockLevel update and flush; success/sold-out outcomes |
| ✔ Redis Re-warming | StockRewarmPort; atomic Redis `SETNX` adapter; successful-fallback-only restoration from durable remaining stock; existing counters preserved; re-warm unavailability does not reverse durable success |

---

## Verification

```text
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL in 25s
71 tests passed, 0 failed, 0 skipped
```

All InventoryService tests are unit tests.

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
- Redis decrement remains atomic Lua using `stock:{saleId}`.
- Lua results remain `-2` cache miss, `-1` sold out, or non-negative stock.
- Executor remains business-decision-free; the Redis adapter translates only
  Redis connection failure into the application-owned unavailable signal.
- Application services depend on ports and access StockLevel through Product.
- PostgreSQL fallback locks the Product aggregate root with
  `PESSIMISTIC_WRITE`, mutates stock through `Product.decrementStock`, and
  persists the managed owned StockLevel in one transaction.
- Successful fallback re-warms `stock:{saleId}` from the durable remaining
  StockCount through `StockRewarmPort`.
- Redis re-warming uses atomic set-if-absent, never overwrites an existing
  counter, and remains best-effort after the durable decrement commits.
- No Kafka, Inventory REST API, Reservation, release, or reconciliation is in scope.

---

## Important Invariants

- SaleService status transitions cannot skip or reverse states.
- StockCount, Product stock, and current stock are never negative.
- Allocation is positive and total allocations never exceed Product stock.
- At most one StockLevel exists per Product + Sale.
- Every StockLevel belongs to its Product and has
  `currentStock <= totalAllocated`.
- Product and StockLevel versions are never negative.
- Failed allocations do not mutate Product or increment its version.
- Redis decrement is never replaced by a client-side read/check/write sequence.
- Cache miss or primary-counter unavailability invokes the fallback port once.
- PostgreSQL fallback performs one authoritative decrement while holding the
  Product aggregate-root lock.
- Only successful fallback invokes re-warming; Redis success and sold-out paths
  do not.
- Re-warming may create a missing counter but never overwrite an existing one.

---

## Known Risks

- Product/JPA version updates are not verified against real PostgreSQL.
- StockCounterService currently loads Product before every Redis decrement.
- PostgreSQL fallback locking and version behavior are unit-tested but not
  verified against live PostgreSQL or under concurrent contention.
- Lua has not been executed against live Redis in tests.
- Redis set-if-absent re-warming and concurrent fallback/re-warm races are
  unit-tested only, not verified against live Redis.
- Re-warmed counters have no TTL because Inventory still has no approved
  sale-end input; TTL ownership remains part of the pre-warm decision.
- Re-warm connection failure preserves durable success but currently emits no
  log or metric.
- No live infrastructure or concurrent correctness tests exist.
- `PROJECT_TRUTH.md` and `REPOSITORY_INDEX.md` remain stale.

---

## Remaining Week 3 Work

- ➡ Pre-warm Use Case
- ➡ Property-based Tests
- ➡ Failure Tests
- ➡ Regression Tests

---

## Next Recommended Task

**Pre-warm Use Case:** approve the sale timing/trigger input, then integrate the
existing `stock-prewarm.lua`. Do not add REST, Kafka, Reservation, release,
reconciliation, or retry logic without separate approval.
