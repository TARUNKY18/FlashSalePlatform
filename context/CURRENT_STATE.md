# CURRENT_STATE.md
**Milestone:** Week 3 — InventoryService
**Status:** 🟡 IN PROGRESS
**Date:** 2026-07-24
**Engineer:** Tarun K Y

---

## Current Status

| Item | Verified state |
|---|---|
| Branch | `main` |
| Latest commit | `2a22457` — `Week3: Complete StockCounterService` |
| Build | `BUILD SUCCESSFUL` in 22s |
| Tests | 55 passed, 0 failed, 0 skipped |

---

## Completed Work

| Slice | Current implementation |
|---|---|
| ✔ InventoryService Skeleton | Gradle module; Java 21; Spring Boot 3.3.4; virtual threads; PostgreSQL, JPA, Flyway, Redis Cluster, and Actuator configuration |
| ✔ Inventory Domain | Framework-free Product aggregate, Product-owned StockLevel, StockCount, ProductId, SaleId, and StockLevelId |
| ✔ Inventory Persistence | Separate JPA entities, isolated mapper, ProductRepository application port and JPA adapter, entity-graph loading, optimistic versions |
| ✔ Inventory Flyway Migration | `products` and `stock_levels` with PK, FK, stock/version checks, and unique Product + Sale allocation |
| ✔ Redis Lua Integration | Approved `stock-decrement.lua`, singleton typed script bean, SHA caching, and Lua executor |
| ✔ Redis Adapter | Redis-neutral StockDecrementPort and decision-free Redis adapter |
| ✔ StockCounterService | Product-owned allocation validation and mapping of cache miss, sold out, and successful decrement outcomes |

---

## Verification

```text
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
BUILD SUCCESSFUL in 22s
55 tests passed, 0 failed, 0 skipped
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
- Executor and Redis adapter remain business-decision-free.
- Application services depend on ports and access StockLevel through Product.
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
- Any fallback must perform exactly one authoritative decrement.

---

## Known Risks

- Product/JPA version updates are not verified against real PostgreSQL.
- StockCounterService currently loads Product before every Redis decrement.
- No approved domain decrement command exists for PostgreSQL fallback.
- Lua has not been executed against live Redis in tests.
- No live infrastructure or concurrent correctness tests exist.
- `PROJECT_TRUTH.md` and `REPOSITORY_INDEX.md` remain stale.

---

## Remaining Week 3 Work

- ➡ PostgreSQL Fallback
- ➡ Redis Re-warming
- ➡ Pre-warm Use Case
- ➡ Property-based Tests
- ➡ Failure Tests
- ➡ Regression Tests

---

## Next Recommended Task

**PostgreSQL Fallback:** implement the Product-owned, transactionally locked
fallback decrement behind an application port. Do not add Redis re-warming or pre-warm.
