# CURRENT_STATE.md
**Milestone:** Week 2 — SaleService Skeleton
**Status:** ✅ COMPLETE
**Date:** 2026-07-03
**Engineer:** Tarun K Y

---

## Completed Work

**SaleService module (38 files):**

- **Domain layer** — `domain/{aggregate,entity,vo,event,exception}/`
  - `FlashSale` aggregate root with sealed `SaleStatus` (Scheduled/Active/Ended/Archived)
  - `SaleSchedule` entity, `SaleWindow` value object
  - `SaleId`, `ProductId`, `EndReason` typed identifiers and enums
  - Four domain events: `SaleScheduled`, `SaleStarted`, `SaleEnded`, `SaleArchived`
  - `SaleCreationException` with error codes EC-002/003/004 per PRD

- **Application layer** — `application/`
  - `SaleCommandService.createSale()` — creates sale in SCHEDULED status
  - `SaleQueryService.getById()` — retrieves sale by ID
  - `CreateSaleCommand` DTO, `SaleNotFoundException`

- **Infra/persistence layer** — `infra/{persistence,config}/`
  - `FlashSaleJpaEntity`, `SaleScheduleJpaEntity`, `SaleStatusHistoryJpaEntity` — JPA mappings
  - `SaleRepository` — sealed-status↔VARCHAR translation, domain↔JPA reconstruction
  - `SpringDataFlashSaleRepository`, `SpringDataSaleScheduleRepository`, `SpringDataSaleStatusHistoryRepository`
  - `ClockConfig` — provides injectable `Clock` bean for testable time

- **API layer** — `api/{,dto}/`
  - `SaleController` — `POST /api/v1/sales` (201), `GET /api/v1/sales/{id}` (200 or 404)
  - `CreateSaleRequest`, `SaleResponse`, `ErrorResponse` DTOs
  - `GlobalExceptionHandler` — maps domain/application exceptions to HTTP responses with PRD error codes

- **Configuration & schema**
  - `application.yml` — port 8081, virtual threads enabled, Flyway + Postgres config
  - `src/main/resources/db/migration/V1__init.sql` — creates `flash_sales`, `sale_schedules`, `sale_status_history` tables with indexes and triggers

- **Tests** — 12 total
  - `FlashSaleStateMachineTest` — 10 tests (4 valid transitions, 4 illegal transitions, 2 supplementary creation-validation)
  - `SaleControllerTest` — 4 slice tests (201 create, 400 validation, 404 not found)

**Gradle multi-module setup:**
- Root `settings.gradle` and `build.gradle` with Java 21 toolchain config
- `gradle/wrapper/` with Gradle 8.10 wrapper (gradlew executable, gradle-wrapper.jar, properties)
- `services/sale-service/build.gradle` with Spring Boot 3.3.4, Data JPA, Validation, Flyway, PostgreSQL driver

---

## Verification

**Build:**
```
./gradlew :services:sale-service:build
BUILD SUCCESSFUL in 32s (8 actionable tasks)
```

**Tests:**
```
./gradlew :services:sale-service:test --rerun-tasks
BUILD SUCCESSFUL in 12s (4 executed)
12 tests passed:
  FlashSaleStateMachineTest: 10 tests PASSED
  SaleControllerTest: 4 tests PASSED
```

---

## State Machine (Implemented)

```
SCHEDULED → ACTIVE → ENDED → ARCHIVED
  ↑          ↓         ↓        ↑
illegal    valid     valid    valid
transitions are guarded by IllegalStateException
```

Transitions implemented as methods on aggregate:
- `FlashSale.schedule(...)` — factory method, validates EC-002/003/004
- `activate(Instant now)` — SCHEDULED → ACTIVE
- `end(Instant now, EndReason reason)` — ACTIVE → ENDED
- `archive(Instant now)` — ENDED → ARCHIVED

---

## Database

**`sales_db`:**
- `flash_sales` — aggregate root, 7 nullable milestone timestamps (scheduled_at always set, others null until transition)
- `sale_schedules` — entity, sale_start/sale_end window per sale
- `sale_status_history` — immutable audit log, one row per state transition (from_status null for initial SCHEDULED entry)

All migrations applied via Flyway V1.

---

## Open Issues

| ID | Description | Priority | Target |
|---|---|---|---|
| P7 | `lua-time-limit 5000ms` too high in redis-node.conf | Medium | Before Week 3 |
| S2 | All ports bind `0.0.0.0` — should be `127.0.0.1` | Low | Week 2 cleanup |
| M14 | `sleep 5` in Makefile too short for cold starts | Low | Week 2 cleanup |

---

## Current Branch

```
main
Last commit: Week 2: SaleService skeleton — FlashSale aggregate, 12 tests passing, Gradle 8.10 wrapper
```

---

## Next Milestone

**Week 3 — InventoryService Skeleton**

- Spring Boot 3 project with Java 21
- `Inventory` aggregate with `Stock` value object
- Redis hot-path cache for `GET /api/v1/inventory/{productId}`
- Lua scripts for atomic stock operations (decrement, release, prewarm)
- Kafka consumer wiring (listen to domain events from `sale-events`)
- Flyway V1 migration for `inventory_db`
- Unit tests covering concurrency via Lua atomicity
- **Done when:** `./gradlew :services:inventory-service:build` succeeds, 8+ tests passing