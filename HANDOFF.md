# Flash Sale Platform — Engineering Handoff

**Handoff date:** 2026-08-06

**Current milestone:** Week 3 — InventoryService

**Week 3 status:** In progress — pre-warm use case implementation complete, pending commit

**Branch:** `main`

**Latest commit:** `84d68ab` (`docs: synchronize project state after ADR-020 Revision 2`), pushed to `origin/main`

**Implementation HEAD:** `f12d67d` (`feat(inventory): add jqwik property-based stock correctness tests`), pushed to `origin/main`

**Working tree:** Pre-warm use case implementation present as untracked/modified files.
`BUILD SUCCESSFUL`, 163 Inventory tests passing. No commit made in SESSION-009.

**Audience:** The senior engineer or Codex session continuing Week 3 development

This is the entry document for the next development session. The pre-warm use case
implementation (SESSION-009) is complete and reviewed (`IMPLEMENTATION APPROVED`),
but **not yet committed**. The first task for the next session is to commit all
pre-warm working-tree files, then complete documentation reconciliation.

The Redis re-warming slice completed at `10069d8`; its request-time `SETNX`
implementation was superseded at `bca1ff1` by revision-fenced synchronization.
ADR-020 (Pre-Warm Architecture) is now approved at Revision 2 following
independent architecture review of six findings, all resolved.

> **Documentation drift warning:** `context/PROJECT_TRUTH.md` and
> `context/REPOSITORY_INDEX.md` still contain stale implementation status.
> Until they are reconciled, current source code, this handoff,
> `context/CURRENT_STATE.md`, and `SESSION-003` through `SESSION-007` in
> `context/SESSION_LOG.md` are the verified implementation evidence. Do not copy
> stale planned fields or structures into code.

---

# Project Overview

## Purpose

Flash Sale Platform is a multi-service backend designed to demonstrate correct
inventory handling under thundering-herd traffic. The system is organized as
independently deployable bounded contexts with database-per-service ownership.
InventoryService owns Product stock, sale-specific StockLevels, and the only
approved atomic stock-decrement path.

## Architecture

The repository is a Gradle multi-module service repository. The intended platform
contains SaleService, InventoryService, OrderService, NotificationService, and
AnalyticsService. SaleService and the approved portion of InventoryService exist
today.

InventoryService follows a hexagonal dependency direction:

```text
domain
  ^
  |
application ----> application ports
  ^                     ^
  |                     |
infrastructure adapters + Spring/JPA/Redis
```

- `domain` contains framework-free aggregates, entities, and value objects.
- `application` orchestrates use cases and interprets infrastructure-neutral
  port results.
- `application.port` defines outbound contracts owned by the application.
- `infra.persistence` implements aggregate persistence with separate JPA models.
- `infra.redis` implements atomic Redis operations without leaking Redis types
  through application ports.
- `infra.config` owns infrastructure bean construction.
- No Inventory REST/API layer exists.
- No Kafka integration exists.
- No Reservation/Week 4 model exists.

The current decrement path is:

```text
StockCounterService
  -> ProductRepository port
  -> Product aggregate / owned StockLevel validation
  -> StockDecrementPort
  -> RedisStockDecrementAdapter
  -> StockDecrementLuaExecutor
  -> one stock-decrement.lua attempt
  -> DurableStockDecrementPort
  -> PostgresStockDecrementAdapter
  -> TransactionalStockDecrement (@Transactional)
  -> SpringDataProductRepository.findByIdForUpdate (PESSIMISTIC_WRITE)
  -> Product.decrementStock
  -> ProductPersistenceMapper.applyCurrentStock
  -> managed StockLevel flush and revision retrieval
  -> PostgreSQL commit
  -> StockProjectionSyncPort
  -> RedisStockProjectionSyncAdapter
  -> StockProjectionSyncLuaExecutor
  -> stock-projection-sync.lua
  -> PostgreSQL-derived Decremented/SoldOut
```

Every recognized Redis result and every indeterminate transport failure invokes
PostgreSQL exactly once. PostgreSQL is authoritative and exclusively determines
the returned result. Post-commit synchronization applies equal/newer StockLevel
revisions, ignores strictly older revisions, invalidates revisionless stock,
preserves stock TTL, and never recreates a missing stock key. Synchronization
failure warns but never conceals the committed result.

## Tech stack

| Concern | Current repository choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.4 |
| Dependency management | Spring dependency-management plugin 1.1.6 |
| Build system | Gradle Wrapper 8.10, multi-module Groovy DSL |
| Web/runtime starter | Spring Web; no Inventory controllers have been added |
| Persistence | Spring Data JPA / Hibernate |
| Relational database | PostgreSQL 16 design; Inventory defaults to `localhost:5433/inventory_db` |
| Schema migrations | Flyway with PostgreSQL support |
| Cache/counter store | Redis Cluster through Spring Data Redis |
| Concurrency model | Java 21 virtual threads enabled |
| Unit testing | JUnit Jupiter, AssertJ/JUnit assertions, Mockito |
| Property testing | jqwik 1.9.0, Inventory test scope only |
| Integration testing | Testcontainers with real PostgreSQL 16 and Redis 7.2 |
| Packaging | Spring Boot executable JAR |

## Java version

Java 21 is configured once in the root `build.gradle` under `subprojects`.
InventoryService inherits that toolchain. Do not duplicate or override the
toolchain inside the module unless the repository-wide decision changes.

## Spring Boot version

InventoryService uses Spring Boot `3.3.4`.

## Build system

Use the checked-in Gradle 8.10 wrapper:

```bash
./gradlew :services:inventory-service:build
```

Do not depend on a machine-installed Gradle distribution.

---

# Documentation Reading Order

This file is the only required pre-read before beginning a new Codex
conversation. Before changing code in that conversation, read the repository
documents in this order:

1. `HANDOFF.md` — current implementation baseline, constraints, and unfinished work.
2. `context/PROJECT_TRUTH.md` — canonical platform architecture and ADR status;
   its implementation-status sections are stale and must not override current
   source evidence.
3. `context/CURRENT_STATE.md` — current operational/milestone snapshot through
   the Property-Based Stock Correctness Tests slice.
4. `docs/architecture/Build-Plan.md` — milestone intent and sequencing; treat
   unapproved legacy details as plans, not implementation requirements.
5. `context/SESSION_LOG.md` — read `SESSION-003` through `SESSION-007` for the
   exhaustive implementation, historical fallback/re-warming, durable-authority
   verification, property-based correctness verification, and decision records.
6. `context/CONFLICTS.md` — check unresolved documentation conflicts before
   acting on contradictory specifications.
7. `context/REPOSITORY_INDEX.md` — repository structure reference; verify it
   against the actual tree because it may lag newly created Inventory files.
8. `docs/adr/01-Decisions.md` — approved platform-level trade-offs.
9. The task-relevant architecture document only:
   - `docs/architecture/DomainModel.md` for domain work.
   - `docs/architecture/DatabaseSchema.md` for schema work.
   - `docs/architecture/RedisDesign.md` for Redis work.
   - `docs/architecture/Build-Plan.md` for milestone boundaries.
10. The current InventoryService source and tests. Repository reality wins over
    stale descriptive status.

If documents conflict, do not resolve the conflict silently. Check
`context/CONFLICTS.md`, identify the exact discrepancy, and request an explicit
decision before changing architecture.

---

# Current Repository State

The implementation began at commit `a2e971c` (`week2-complete`) and produced
these approved implementation commits:

| Commit | Approved slice |
|---|---|
| `0444c9b` | InventoryService module skeleton |
| `213570a` | Framework-free Inventory domain model |
| `eecc75c` | Persistence, Flyway V1, Redis Lua integration, and Redis adapter |
| `2a22457` | ProductRepository application port and StockCounterService |
| `9bb3ad7` | Product-owned, transactionally locked PostgreSQL fallback |
| `10069d8` | Safe Redis re-warming after successful PostgreSQL fallback |
| `bca1ff1` | PostgreSQL durable authority, revision-fenced Redis projection, and real infrastructure correctness tests |
| `f12d67d` | jqwik property-based stock correctness tests |

InventoryService currently contains (working tree — pre-warm implementation not yet committed):

- 37 production Java files (+7 untracked pre-warm files).
- 22 runnable test classes (+4 untracked pre-warm test classes).
- 163 passing Inventory tests: 135 unit/property and 28 real Testcontainers tests.
- Three Lua scripts: `stock-decrement.lua`, `stock-projection-sync.lua`, and
  `stock-prewarm.lua` (modified/untracked).
- Two Flyway-managed tables: `products` and `stock_levels`.
- No REST endpoints, Kafka code, Reservation work, release integration, or
  reconciliation integration.

## ✔ Skeleton

The InventoryService Gradle module is included as
`services:inventory-service`.

Implemented:

- Spring Boot application entry point in root package
  `com.flashsale.inventory`.
- Spring Boot 3.3.4 and dependency-management 1.1.6.
- Spring Web, Data JPA, Data Redis, Actuator, Flyway, PostgreSQL, and test
  dependencies.
- Java 21 inherited from the root Gradle toolchain.
- Virtual threads enabled with
  `spring.threads.virtual.enabled: true`.
- Service port `${INVENTORY_SERVICE_PORT:8082}`.
- PostgreSQL connection defaults for `inventory_db`.
- JPA schema validation and disabled Open Session in View.
- Flyway migration location `classpath:db/migration`.
- Redis Cluster nodes supplied through
  `SPRING_DATA_REDIS_CLUSTER_NODES`.
- Actuator `health` and `info` exposure.

No API controller or business logic was added as part of the skeleton.

## ✔ Domain

The domain is framework-free and isolated under
`com.flashsale.inventory.domain`.

Implemented:

- `Product` aggregate root owns all StockLevels for a Product.
- `StockLevel` represents one Product allocation for one Sale.
- `StockCount` represents a non-negative stock quantity.
- Typed UUID identities: `ProductId`, `SaleId`, and `StockLevelId`.
- Product creation, reconstitution, stock allocation, Product-owned durable
  stock decrement, lookup by SaleId, allocated-stock calculation, and
  available-to-allocate calculation.
- StockLevel creation and persistence-safe reconstitution.
- Immutable collection snapshots from Product.
- Aggregate and entity version preservation during reconstitution.

Enforced rules:

- Total Product stock is non-negative.
- Allocation quantity is strictly positive.
- Sum of sale allocations cannot exceed Product total stock.
- A Product can have only one StockLevel for a given SaleId.
- A reconstituted StockLevel must belong to its Product.
- Current stock is between zero and total allocation, inclusive.
- Product and StockLevel versions cannot be negative.
- Failed allocation is atomic and does not increment Product version.
- Insufficient durable stock does not mutate the StockLevel.
- A successful durable decrement replaces the immutable owned StockLevel,
  reduces current stock exactly once, and advances its domain version.

The domain imports no Spring, JPA, Hibernate, Redis, or Kafka types.

## ✔ Persistence

Persistence uses separate mutable JPA entities and a dedicated mapper. Domain
classes were not annotated or changed.

Implemented:

- `ProductJpaEntity` maps the aggregate root to `products`.
- `StockLevelJpaEntity` maps the owned entity to `stock_levels`.
- Product-to-StockLevel ownership uses `@OneToMany`, cascade-all,
  orphan removal, and a child-owned `product_id` foreign key.
- Both persistence entities use `@Version`.
- `ProductPersistenceMapper` maps the complete aggregate tree in both
  directions and preserves IDs, quantities, ownership, and versions.
- `SpringDataProductRepository` loads Product with StockLevels through an
  entity graph.
- Infrastructure `ProductRepository` implements the application
  `ProductRepository` port and supports aggregate `findById` and
  `saveAndFlush`.

There is deliberately no independent StockLevel repository. StockLevels may
only be persisted through Product.

## ✔ Flyway

`V1__init.sql` creates only the schema represented by the approved persistence
model.

`products`:

- `id UUID` primary key.
- `total_stock INTEGER NOT NULL` with `total_stock >= 0`.
- `version BIGINT NOT NULL` with `version >= 0`.

`stock_levels`:

- `id UUID` primary key.
- `product_id UUID NOT NULL` foreign key to `products(id)`.
- `sale_id UUID NOT NULL` as an opaque cross-service identifier.
- `total_allocated INTEGER NOT NULL` with `total_allocated > 0`.
- `current_stock INTEGER NOT NULL` with
  `0 <= current_stock <= total_allocated`.
- `version BIGINT NOT NULL` with `version >= 0`.
- Unique constraint on `(product_id, sale_id)`.
- `ON DELETE RESTRICT` on the Product foreign key.

Index decisions:

- PostgreSQL primary-key indexes serve ID lookups.
- The Product + Sale unique constraint supplies a B-tree beginning with
  `product_id`, so no redundant child FK index was added.
- No cross-database foreign key is created for SaleId.
- IDs are application-assigned; the database has no UUID defaults.

No Reservation, release, reconciliation, audit, outbox, Kafka, or Week 4 table
exists.

## ✔ Redis Lua Integration

`RedisScriptConfiguration` loads two typed singleton scripts:

- `stock-decrement.lua` performs the one allowed Redis decrement attempt and
  retains the `-2` cache-miss, `-1` sold-out, and non-negative remaining-stock
  return contract.
- `stock-projection-sync.lua` synchronizes PostgreSQL-derived stock and
  StockLevel revision after commit. It applies equal/newer revisions, ignores
  older revisions, invalidates revisionless stock, preserves stock TTL, and
  does not recreate a missing stock key.

`StockDecrementLuaExecutor` and `StockProjectionSyncLuaExecutor` exclusively
own key construction, serialization, and Redis script execution mechanics.
Both scripts are exercised against Redis 7.2 by Testcontainers tests.

## ✔ Redis Adapter

`StockDecrementPort` is an infrastructure-neutral outbound application port:

```text
decrement(SaleId, int) -> Long
```

It exposes no Redis, Lua, Spring, key, or serialization type.

`RedisStockDecrementAdapter` implements the port by delegating exactly once to
`StockDecrementLuaExecutor`. It preserves deterministic Lua results and marks
only connection, timeout, and transport failures as indeterminate through the
application-owned `StockDecrementUnavailableException`. Deterministic Redis
failures fail closed.

## ✔ StockCounterService

`StockCounterService` is the current decrement use-case orchestrator.

Current behavior:

1. Validate Product ownership and the requested quantity through the
   framework-free Product aggregate.
2. Attempt `StockDecrementPort` exactly once.
3. For every recognized Lua result, or an indeterminate Redis transport
   failure, invoke `DurableStockDecrementPort` exactly once.
4. Return only the PostgreSQL-derived `Decremented` or `SoldOut` result.
5. After the durable transaction commits, invoke `StockProjectionSyncPort`
   with the authoritative remaining stock and child revision.
6. Preserve the committed result if projection synchronization is unavailable
   or returns no result.

There is no retry, compensation, REST contract, or Redis-only success path.

## ✔ PostgreSQL Durable Authority

`DurableStockDecrementPort` is the infrastructure-neutral boundary for one
authoritative decrement. `PostgresStockDecrementAdapter` delegates to
`TransactionalStockDecrement`, whose proxied `@Transactional` method locks the
Product aggregate root with `PESSIMISTIC_WRITE`, applies the Product-owned
decrement, flushes the managed StockLevel, and returns the remaining stock plus
post-flush revision. The port does not return until the transaction commits.

PostgreSQL exclusively decides success or sold out. The Redis attempt is only
a projection-side operation and never determines the client-visible result.

## ✔ Redis Re-warming

The no-overwrite `SETNX` re-warming slice was completed in `10069d8`. Revision
2 then superseded that request-time bridge in `bca1ff1`: `StockRewarmPort`,
`RedisStockRewarmAdapter`, and their unavailable exception were removed.

Their replacement is revision-fenced post-commit projection synchronization:
`StockProjectionSyncPort` and `RedisStockProjectionSyncAdapter` update
`stock:{saleId}` together with `stock:version:{saleId}` only when the durable
revision is not older. Missing stock is intentionally left missing for the
separate pre-warm use case.

---

# New Packages

## Production packages

| Package | Responsibility |
|---|---|
| `com.flashsale.inventory` | InventoryService Spring Boot composition root |
| `com.flashsale.inventory.application` | Inventory application use cases and use-case results |
| `com.flashsale.inventory.application.port` | Infrastructure-neutral outbound contracts |
| `com.flashsale.inventory.domain.aggregate` | Inventory aggregate roots |
| `com.flashsale.inventory.domain.entity` | Entities owned by Inventory aggregates |
| `com.flashsale.inventory.domain.vo` | Immutable domain value objects and typed identities |
| `com.flashsale.inventory.infra.config` | Spring infrastructure bean configuration |
| `com.flashsale.inventory.infra.persistence` | JPA entities, mapping, and persistence adapters |
| `com.flashsale.inventory.infra.redis` | Redis/Lua execution and outbound port adapters |

## Test packages

Tests mirror these production boundaries:

- `com.flashsale.inventory.application`
- `com.flashsale.inventory.domain.aggregate`
- `com.flashsale.inventory.domain.entity`
- `com.flashsale.inventory.domain.vo`
- `com.flashsale.inventory.infra.config`
- `com.flashsale.inventory.infra.persistence`
- `com.flashsale.inventory.infra.redis`
- `com.flashsale.inventory.integration`

## Resource paths

- `services/inventory-service/src/main/resources/db/migration` — Inventory
  Flyway migrations.
- `services/inventory-service/src/main/resources/lua` — approved Redis Lua
  resources. `stock-decrement.lua` and `stock-projection-sync.lua` are
  integrated.

---

# New Classes

“Class” below includes Java classes, records, sealed interfaces, and application
ports introduced during this session.

## Production types

### `InventoryServiceApplication`

- **Package:** `com.flashsale.inventory`
- **Responsibility:** Starts InventoryService and defines the component-scan
  root.
- **Dependencies:** Spring Boot `SpringApplication` and
  `@SpringBootApplication`.
- **Why it exists:** Every independently runnable Spring Boot service needs a
  composition entry point.

### `Product`

- **Package:** `com.flashsale.inventory.domain.aggregate`
- **Responsibility:** Aggregate root for Product stock and every sale-specific
  StockLevel owned by that Product.
- **Dependencies:** Domain `StockLevel`, `ProductId`, `SaleId`, `StockCount`,
  and Java collections only.
- **Why it exists:** Allocation ownership, uniqueness, and aggregate-wide stock
  limits must be enforced in one domain authority rather than in services or
  repositories.

### `StockLevel`

- **Package:** `com.flashsale.inventory.domain.entity`
- **Responsibility:** Identified sale allocation within Product, holding total
  allocated stock, current stock, and version.
- **Dependencies:** `StockLevelId`, `ProductId`, `SaleId`, `StockCount`, and
  Java `Objects`.
- **Why it exists:** A Product needs one independently identified allocation
  record per Sale while retaining aggregate ownership.

### `StockCount`

- **Package:** `com.flashsale.inventory.domain.vo`
- **Responsibility:** Immutable non-negative stock quantity with availability,
  sold-out, checked increment, checked decrement, and decrement feasibility
  operations.
- **Dependencies:** Java primitives and exact arithmetic only.
- **Why it exists:** Non-negative stock and arithmetic safety must be expressed
  once as a domain type instead of repeated primitive checks.

### `ProductId`

- **Package:** `com.flashsale.inventory.domain.vo`
- **Responsibility:** UUID-backed Product identity with generation and parsing
  factories.
- **Dependencies:** `java.util.UUID` and `Objects`.
- **Why it exists:** Prevents raw UUIDs and identities for different concepts
  from being mixed at domain boundaries.

### `SaleId`

- **Package:** `com.flashsale.inventory.domain.vo`
- **Responsibility:** UUID-backed opaque reference to a Sale owned by
  SaleService.
- **Dependencies:** `java.util.UUID` and `Objects`; no SaleService classes.
- **Why it exists:** Preserves bounded-context independence while retaining a
  strongly typed cross-service reference.

### `StockLevelId`

- **Package:** `com.flashsale.inventory.domain.vo`
- **Responsibility:** UUID-backed identity for StockLevel.
- **Dependencies:** `java.util.UUID` and `Objects`.
- **Why it exists:** Gives StockLevel stable identity independent of the
  separate Product + Sale uniqueness rule.

### `ProductRepository` application port

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Defines aggregate-level `findById(ProductId)` and
  `save(Product)` operations.
- **Dependencies:** Domain `Product`, `ProductId`, and Java `Optional`.
- **Why it exists:** Application code must depend on an owned contract rather
  than on Spring Data or JPA.

### `StockDecrementPort`

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Defines atomic stock decrement using domain SaleId and a
  raw `Long` result.
- **Dependencies:** Domain `SaleId` only.
- **Why it exists:** Separates the decrement use case from Redis, Lua, key
  construction, serialization, and Spring infrastructure.

### `StockDecrementUnavailableException`

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Infrastructure-neutral signal that the Redis attempt has
  an indeterminate transport outcome.
- **Dependencies:** Java `RuntimeException` only.
- **Why it exists:** Lets application orchestration continue to durable
  authority without importing Redis exception types.

### `DurableStockDecrementPort`

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Defines exactly one authoritative PostgreSQL decrement
  using ProductId, SaleId, and quantity.
- **Dependencies:** Domain typed IDs and application
  `DurableStockDecrementResult`.
- **Why it exists:** Keeps locking, transaction, JPA, and flush mechanics behind
  an application-owned boundary.

### `DurableStockUnavailableException`

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Infrastructure-neutral signal that durable authority is
  unavailable.
- **Dependencies:** Java `RuntimeException` only.

### `StockProjectionSyncPort`

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Synchronizes authoritative stock and revision into the
  Redis projection after commit.
- **Dependencies:** Domain `SaleId` and application projection result types.
- **Why it exists:** Keeps Redis revision-fencing mechanics outside application
  orchestration.

### `StockProjectionSyncUnavailableException`

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Infrastructure-neutral signal that post-commit projection
  synchronization was unavailable.
- **Dependencies:** Java `RuntimeException` only.

### `StockCounterService`

- **Package:** `com.flashsale.inventory.application`
- **Responsibility:** Orchestrates one validated decrement attempt through
  Product ownership and application ports.
- **Dependencies:** Application `ProductRepository`, `StockDecrementPort`,
  `DurableStockDecrementPort`, `StockProjectionSyncPort`, and their
  infrastructure-neutral result/failure types; domain `Product`, `StockLevel`,
  `ProductId`, `SaleId`, and `StockCount`; Spring `@Service`.
- **Why it exists:** Result interpretation and use-case sequencing belong
  outside both the domain model and Redis adapter.

### `StockDecrementResult`

- **Package:** `com.flashsale.inventory.application`
- **Responsibility:** Sealed application outcome for decrement.
- **Dependencies:** Domain `StockCount` and Java `Objects`.
- **Why it exists:** Makes authoritative success and sold out explicit without
  leaking Redis numeric codes to callers.
- **Nested result records:**
  - `Decremented(StockCount remainingStock)` — successful decrement.
  - `SoldOut()` — PostgreSQL reported insufficient stock.

### `DurableStockDecrementResult`

- **Package:** `com.flashsale.inventory.application`
- **Responsibility:** Carries the authoritative success/sold-out decision,
  remaining stock, and StockLevel revision across the durable port.

### `StockProjectionSyncResult`

- **Package:** `com.flashsale.inventory.application`
- **Responsibility:** Represents applied, stale-ignored, and missing
  projection-sync outcomes without exposing Redis types.

### `ProductJpaEntity`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Mutable JPA mapping for Product and its owned
  StockLevels.
- **Dependencies:** Jakarta Persistence annotations, `UUID`, Java collections,
  and `StockLevelJpaEntity`.
- **Why it exists:** The domain must stay framework-free while Hibernate still
  receives a conventional mutable entity graph.

### `StockLevelJpaEntity`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Mutable JPA mapping for a StockLevel row, Product foreign
  key, SaleId, allocation/current stock, and optimistic version.
- **Dependencies:** Jakarta Persistence annotations, `UUID`, and
  `ProductJpaEntity`.
- **Why it exists:** Persists the child entity without annotating or weakening
  the framework-free domain model.

### `ProductPersistenceMapper`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Maps the complete Product/StockLevel tree between domain
  and JPA representations, including ownership and versions, and applies
  authoritative current-stock state to the matching managed StockLevel.
- **Dependencies:** All Inventory domain types, both JPA entity types, and
  Spring `@Component`.
- **Why it exists:** Keeps all persistence translation in one explicit boundary
  and ensures invalid persisted state is revalidated by the domain.

### `SpringDataProductRepository`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Spring Data CRUD repository for `ProductJpaEntity` with an
  entity-graph `findById` that fetches owned StockLevels and a
  `PESSIMISTIC_WRITE` Product-root lookup for durable decrement.
- **Dependencies:** Spring Data JPA, `ProductJpaEntity`, `UUID`, and
  `Optional`.
- **Why it exists:** Supplies JPA mechanics while keeping Spring Data types
  behind the infrastructure adapter.

### `ProductRepository` persistence adapter

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Implements the application ProductRepository port,
  maps aggregates, and wraps load/save operations in transactions.
- **Dependencies:** `SpringDataProductRepository`,
  `ProductPersistenceMapper`, domain Product/ProductId, Spring
  `@Repository`, and `@Transactional`.
- **Why it exists:** Connects the application-owned repository contract to JPA
  without exposing JPA entities outside infrastructure.

### `PostgresStockDecrementAdapter`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Implements `DurableStockDecrementPort` and delegates to
  the proxied transactional component.
- **Dependencies:** Application durable port/result types and
  `TransactionalStockDecrement`.
- **Why it exists:** Returns from the port only after the authoritative
  transaction has committed.

### `TransactionalStockDecrement`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Owns the `@Transactional` Product-root lock, domain
  decrement, managed StockLevel update, flush, and revision retrieval.
- **Why it exists:** Keeps the transaction boundary proxy-visible and prevents
  Redis I/O from occurring inside the PostgreSQL transaction.

### `RedisScriptConfiguration`

- **Package:** `com.flashsale.inventory.infra.config`
- **Responsibility:** Loads `lua/stock-decrement.lua` and
  `lua/stock-projection-sync.lua` as singleton typed script beans.
- **Dependencies:** Spring configuration/resource APIs and Spring Data Redis
  script support.
- **Why it exists:** Centralizes classpath script loading, result typing, and
  reusable script SHA calculation.

### `StockDecrementLuaExecutor`

- **Package:** `com.flashsale.inventory.infra.redis`
- **Responsibility:** Builds `stock:{saleId}`, serializes quantity, executes the
  configured Lua script, and returns the raw nullable result.
- **Dependencies:** `SaleId`, `StringRedisTemplate`,
  qualified `RedisScript<Long>`, and Spring `@Component`.
- **Why it exists:** Encapsulates Redis-specific key, serialization, and script
  execution mechanics in one thin infrastructure class.

### `StockProjectionSyncLuaExecutor`

- **Package:** `com.flashsale.inventory.infra.redis`
- **Responsibility:** Executes revision-fenced synchronization for
  `stock:{saleId}` and `stock:version:{saleId}` with authoritative stock and
  revision arguments.
- **Why it exists:** Encapsulates projection key, serialization, and script
  execution mechanics without application decisions.

### `RedisStockDecrementAdapter`

- **Package:** `com.flashsale.inventory.infra.redis`
- **Responsibility:** Implements `StockDecrementPort`, delegates to
  `StockDecrementLuaExecutor`, preserves deterministic results, and translates
  indeterminate Redis transport failures to the application-owned unavailable
  signal.
- **Dependencies:** Application `StockDecrementPort` and
  `StockDecrementUnavailableException`, domain `SaleId`, Redis exception type,
  `StockDecrementLuaExecutor`, and Spring `@Component`.
- **Why it exists:** Provides the hexagonal adapter boundary while keeping
  Redis exception types out of application orchestration.

### `RedisStockProjectionSyncAdapter`

- **Package:** `com.flashsale.inventory.infra.redis`
- **Responsibility:** Implements `StockProjectionSyncPort`, maps Lua outcomes,
  and translates projection transport failure without concealing committed
  durable success.
- **Dependencies:** Application projection port/result/failure types,
  `StockProjectionSyncLuaExecutor`, and Spring `@Component`.
- **Why it exists:** Enforces the post-commit revision fence behind the
  application-owned port.

## Test classes

### `ProductTest`

- **Responsibility:** Verifies Product creation, allocations, allocation
  ceiling, duplicate Sale rejection, ownership, immutable snapshots, failed
  allocation atomicity, reconstitution, successful durable decrement,
  insufficient-stock non-mutation, and decrement validation.
- **Dependencies:** JUnit Jupiter and Inventory domain types.
- **Why it exists:** Protects aggregate-wide invariants.

### `StockLevelTest`

- **Responsibility:** Verifies allocation initialization, reconstitution, zero
  stock, allocation ceiling, and version boundaries.
- **Dependencies:** JUnit Jupiter and Inventory domain types.
- **Why it exists:** Protects StockLevel construction invariants.

### `StockCountTest`

- **Responsibility:** Verifies negative rejection, availability, sold-out
  behavior, checked immutable arithmetic, positive quantities, underflow, and
  overflow.
- **Dependencies:** JUnit Jupiter and `StockCount`.
- **Why it exists:** Protects the primitive stock safety boundary.

### `TypedIdTest`

- **Responsibility:** Verifies UUID/string factories, null rejection, type
  distinction, and generated-ID uniqueness.
- **Dependencies:** JUnit Jupiter and Inventory typed IDs.
- **Why it exists:** Prevents regression to unsafe or nullable raw identities.

### `ProductPersistenceMapperTest`

- **Responsibility:** Verifies complete domain-to-JPA and JPA-to-domain mapping,
  bidirectional ownership, version preservation, and invalid persisted-state
  rejection.
- **Dependencies:** JUnit Jupiter, domain types, JPA entity types, and
  `ProductPersistenceMapper`.
- **Why it exists:** Protects the isolation and fidelity of the domain/JPA
  translation boundary.

### `ProductRepositoryTest`

- **Responsibility:** Verifies load, missing Product behavior, mapper
  delegation, `saveAndFlush`, and returned aggregate mapping.
- **Dependencies:** JUnit Jupiter, Mockito, domain types,
  `SpringDataProductRepository`, and `ProductPersistenceMapper`.
- **Why it exists:** Confirms the persistence adapter honors the application
  repository contract without requiring a database.

### `RedisScriptConfigurationTest`

- **Responsibility:** Verifies classpath loading, result types, and singleton
  bean identity for both integrated Lua scripts.
- **Dependencies:** JUnit Jupiter, Spring
  `AnnotationConfigApplicationContext`, and Redis script abstractions.
- **Why it exists:** Detects missing, altered, or repeatedly constructed Lua
  script resources.

### `StockDecrementLuaExecutorTest`

- **Responsibility:** Verifies hash-tagged key, quantity serialization,
  `StringRedisTemplate` delegation, raw result preservation, and null SaleId
  rejection.
- **Dependencies:** JUnit Jupiter, Mockito, `StringRedisTemplate`,
  `RedisScript`, and `SaleId`.
- **Why it exists:** Protects Redis execution wiring without embedding business
  interpretation.

### `RedisStockDecrementAdapterTest`

- **Responsibility:** Verifies exact SaleId/quantity delegation, deterministic
  result propagation, indeterminate transport classification, and fail-closed
  handling of deterministic failures.
- **Dependencies:** JUnit Jupiter, Mockito, `StockDecrementPort`,
  `StockDecrementLuaExecutor`, and `SaleId`.
- **Why it exists:** Ensures the adapter remains a decision-free delegation
  boundary.

### `StockCounterServiceTest`

- **Responsibility:** Verifies one Redis attempt, exactly one durable decrement
  for every recognized result or indeterminate failure, PostgreSQL-only result
  authority, post-commit projection sync, committed-result preservation, and
  invalid input/result handling.
- **Dependencies:** JUnit Jupiter, Mockito, application ports and results, and
  Inventory domain types.
- **Why it exists:** Protects the approved use-case orchestration and prevents
  retries, double durable invocation, or projection authority inversion.

### `StockProjectionSyncLuaExecutorTest`

- **Responsibility:** Verifies same-slot stock/version keys, authoritative
  stock/revision serialization, and raw Lua result propagation.

### `RedisStockProjectionSyncAdapterTest`

- **Responsibility:** Verifies projection result mapping and unavailable/invalid
  result handling behind the application-owned port.

### `PostgresStockDecrementAdapterTest` and `TransactionalStockDecrementTest`

- **Responsibility:** Verifies locked Product lookup, Product-owned decrement,
  managed StockLevel update/flush, sold-out non-mutation, missing Product
  handling, `PESSIMISTIC_WRITE`, the proxy-visible transaction boundary, and
  commit-before-port-return sequencing.
- **Dependencies:** JUnit Jupiter, Mockito, Inventory domain/JPA types, Spring
  Data lock metadata, and Spring transaction metadata.
- **Why it exists:** Protects the durable transaction and aggregate-locking
  contract.

### Testcontainers integration suites

- **Classes:** `DurableStockDecrementIntegrationTest`,
  `RedisPostgresFailoverIntegrationTest`, and
  `StockProjectionSyncIntegrationTest`.
- **Responsibility:** Verify real PostgreSQL locking/version behavior, real
  Redis Lua behavior, recognized/indeterminate failover sequencing,
  revision-fenced synchronization, TTL preservation, missing-key behavior,
  and zero oversell under concurrency.

---

# Modified Files

This section lists every file created or changed by the completed approved
implementation slices, the session log updates, and this handoff.

## Existing repository files changed

| File | Why it changed |
|---|---|
| `settings.gradle` | Added `include 'services:inventory-service'` to the multi-module build. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductRepository.java` | Initially added as the JPA aggregate adapter; later updated only to implement the application ProductRepository port and add `@Override` markers. |
| `context/SESSION_LOG.md` | Appended `SESSION-003` through `SESSION-007`; previous history was preserved. |
| `context/CURRENT_STATE.md` | Updated the verified milestone snapshot through the Property-Based Stock Correctness Tests slice. |
| `HANDOFF.md` | Updated this production handoff through the Property-Based Stock Correctness Tests slice. |

## New skeleton/configuration files

| File | Why it exists |
|---|---|
| `services/inventory-service/build.gradle` | Defines the InventoryService Spring Boot module and its dependencies, including jqwik 1.9.0 in test scope only. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/InventoryServiceApplication.java` | InventoryService Spring Boot entry point. |
| `services/inventory-service/src/main/resources/application.yml` | Service port, virtual threads, PostgreSQL, JPA, Flyway, Redis Cluster, and Actuator configuration. |

## New domain files

| File | Why it exists |
|---|---|
| `services/inventory-service/src/main/java/com/flashsale/inventory/domain/aggregate/Product.java` | Product aggregate and allocation invariants. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/domain/entity/StockLevel.java` | Sale-specific stock allocation owned by Product. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/domain/vo/ProductId.java` | Typed Product UUID. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/domain/vo/SaleId.java` | Typed opaque Sale UUID. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/domain/vo/StockCount.java` | Non-negative stock value and checked arithmetic. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/domain/vo/StockLevelId.java` | Typed StockLevel UUID. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/domain/aggregate/ProductTest.java` | Product invariant unit tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/domain/aggregate/ProductStockCorrectnessPropertyTest.java` | Five jqwik properties covering exact decrement, non-negative stock, insufficient-stock non-mutation, exact depletion, repeated operations, boundaries, and overflow-safe generated quantities. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/domain/entity/StockLevelTest.java` | StockLevel invariant unit tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/domain/vo/StockCountTest.java` | StockCount behavior unit tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/domain/vo/TypedIdTest.java` | Typed identity unit tests. |

## New persistence and migration files

| File | Why it exists |
|---|---|
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductJpaEntity.java` | Separate JPA Product representation. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/StockLevelJpaEntity.java` | Separate JPA StockLevel representation. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductPersistenceMapper.java` | Complete domain/JPA translation boundary. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/SpringDataProductRepository.java` | Spring Data repository with aggregate entity-graph loading. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/ProductPersistenceMapperTest.java` | Mapper fidelity and invalid-state tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/ProductRepositoryTest.java` | Aggregate persistence adapter unit tests. |
| `services/inventory-service/src/main/resources/db/migration/V1__init.sql` | Flyway V1 schema for Product and StockLevel only. |

## New Redis Lua integration and adapter files

| File | Why it exists |
|---|---|
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/config/RedisScriptConfiguration.java` | Loads the approved decrement script as a singleton typed bean. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/StockDecrementLuaExecutor.java` | Owns Redis key/argument/script execution mechanics. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockDecrementPort.java` | Redis-neutral atomic decrement port. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapter.java` | Connects the decrement port to the Lua executor. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/config/RedisScriptConfigurationTest.java` | Script loading and SHA tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/StockDecrementLuaExecutorTest.java` | Lua executor wiring tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapterTest.java` | Redis adapter delegation tests. |

## New StockCounterService files

| File | Why it exists |
|---|---|
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/ProductRepository.java` | Application-owned aggregate persistence port. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java` | Current stock-decrement application orchestration. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockDecrementResult.java` | Explicit sealed decrement outcomes. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/application/StockCounterServiceTest.java` | Focused orchestration and failure unit tests. |

## PostgreSQL fallback slice files

| File | Fallback change |
|---|---|
| `services/inventory-service/src/main/java/com/flashsale/inventory/domain/aggregate/Product.java` | Added the Product-owned durable decrement command and success/sold-out behavior. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java` | Added one fallback invocation on cache miss or primary-counter unavailability. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockDecrementResult.java` | Clarified that cache misses are resolved internally before return. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockFallbackPort.java` | Added the infrastructure-neutral durable-decrement port. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockDecrementUnavailableException.java` | Added the application-owned primary-counter unavailable signal. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/PostgresStockFallbackAdapter.java` | Added the transactional PostgreSQL fallback adapter. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/SpringDataProductRepository.java` | Added the Product-root `PESSIMISTIC_WRITE` lookup. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductPersistenceMapper.java` | Added managed current-stock application through the existing mapping boundary. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/StockLevelJpaEntity.java` | Added guarded managed current-stock update mechanics. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapter.java` | Added Redis connection-failure translation. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/domain/aggregate/ProductTest.java` | Added Product decrement success, sold-out, validation, and non-mutation tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/application/StockCounterServiceTest.java` | Added cache-miss/unavailable fallback and no-retry orchestration tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/persistence/PostgresStockFallbackAdapterTest.java` | Added transaction, lock, managed-update, flush, and sold-out tests. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockDecrementAdapterTest.java` | Added connection-failure translation coverage. |

## Redis re-warming slice files

| File | Re-warming change |
|---|---|
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/StockCounterService.java` | Added successful-fallback-only re-warming and durable-success preservation when Redis remains unavailable. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockRewarmPort.java` | Added the infrastructure-neutral no-overwrite re-warming port. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/application/port/StockRewarmUnavailableException.java` | Added the application-owned re-warm-unavailable signal. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/redis/RedisStockRewarmAdapter.java` | Added atomic Redis set-if-absent restoration using durable remaining stock. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/application/StockCounterServiceTest.java` | Added re-warm orchestration, ineligible-path, and failure-safe assertions. |
| `services/inventory-service/src/test/java/com/flashsale/inventory/infra/redis/RedisStockRewarmAdapterTest.java` | Added missing/existing counter and connection-failure tests. |

This slice was completed in `10069d8`. Its request-time `SETNX` bridge was
subsequently removed by the Revision 2 durable-authority implementation.

## Durable-authority correctness slice files

| Area | Files and purpose |
|---|---|
| Application results | `DurableStockDecrementResult.java` and `StockProjectionSyncResult.java` carry durable and projection outcomes without infrastructure types. |
| New application ports | `DurableStockDecrementPort.java`, `DurableStockUnavailableException.java`, `StockProjectionSyncPort.java`, and `StockProjectionSyncUnavailableException.java`. |
| Durable adapters | `PostgresStockDecrementAdapter.java` and `TransactionalStockDecrement.java` separate port return from the proxied transaction boundary. |
| Projection adapters | `StockProjectionSyncLuaExecutor.java` and `RedisStockProjectionSyncAdapter.java` implement revision-fenced post-commit synchronization. |
| Lua resource | `stock-projection-sync.lua` atomically fences stock updates by durable revision, preserves TTL, invalidates revisionless state, and leaves missing stock absent. |
| Modified orchestration | `StockCounterService.java`, `RedisStockDecrementAdapter.java`, `RedisScriptConfiguration.java`, Product/JPA mapping types, and the Inventory Gradle/configuration files implement the frozen Revision 2 flow and its test support. |
| Unit tests | Existing affected tests were updated; new coverage was added for the durable adapter/component and projection executor/adapter. |
| Integration tests | `DurableStockDecrementIntegrationTest.java`, `RedisPostgresFailoverIntegrationTest.java`, `StockProjectionSyncIntegrationTest.java`, and shared `InventoryInfrastructureTestSupport.java` run against PostgreSQL 16 and Redis 7.2 containers. |
| Removed compatibility bridge | `StockFallbackPort`, `StockRewarmPort`, their unavailable exceptions, `PostgresStockFallbackAdapter`, `RedisStockRewarmAdapter`, and their obsolete tests were removed. |

## Approved resource used unchanged

`services/inventory-service/src/main/resources/lua/stock-decrement.lua` existed
before the Java integration and remains integrated. The pre-existing
`stock-prewarm.lua`, `stock-release.lua`, and `stock-reconcile.lua` resources
remain unintegrated.

No SaleService file was modified.

---

# Architecture Decisions

1. **Hexagonal boundaries are mandatory.** Domain is innermost; application
   depends on domain and owned ports; infrastructure implements ports.
2. **Java 21 is repository-wide.** The root Gradle toolchain owns the version.
3. **Spring Boot version is 3.3.4.**
4. **Virtual threads are enabled per service.**
5. **Domain purity is non-negotiable.** No Spring, JPA, Hibernate, Redis, or
   Kafka annotations/imports may enter Inventory domain packages.
6. **Product is the aggregate root.** StockLevel is owned by Product and is not
   independently mutated or persisted.
7. **One StockLevel per Product + Sale is enforced twice.** Product enforces it
   in memory; JPA/Flyway enforce `(product_id, sale_id)` uniqueness.
8. **StockLevelId remains UUID-backed.** Product + Sale uniqueness is a separate
   business/database invariant, not the StockLevel primary-key shape.
9. **Typed IDs are used at domain/application boundaries.** Raw UUIDs remain in
   persistence mechanics only.
10. **SaleId is opaque.** Inventory imports no SaleService domain types and
    creates no cross-database Sale foreign key.
11. **Persistence models are separate.** Domain classes are not JPA entities.
12. **Mapping is isolated.** `ProductPersistenceMapper` is the sole
    domain/JPA translation boundary.
13. **The complete aggregate is loaded for mapping.** Spring Data uses an
    entity graph for Product + StockLevels.
14. **There is no child repository.** Introducing an unrestricted
    StockLevel repository would bypass aggregate ownership.
15. **Both JPA entities use optimistic versions.** Corresponding Flyway columns
    are non-null, non-negative `BIGINT`s.
16. **Product owns persistence lifecycle.** Cascade-all and orphan removal apply
    to StockLevels; the child owns the physical foreign key.
17. **V1 is intentionally minimal.** Schema follows approved JPA reality, not
    stale documents containing SKU, price, timestamps, audit, Redis, or
    reconciliation columns.
18. **UUIDs are application-assigned.** Flyway defines no database UUID
    defaults.
19. **Indexes are not duplicated.** Primary-key and unique-constraint indexes
    are reused for approved lookups.
20. **Flyway is authoritative for schema creation.** Hibernate runs with
    `ddl-auto: validate`, never create/update.
21. **The Lua resource owns atomic decrement logic.** Java loads and executes
    the approved script rather than recreating it.
22. **Redis decrement must remain server-atomic.** A client-side
    GET/check/DECR sequence is forbidden.
23. **The Redis Cluster key is `stock:{saleId}`.** The SaleId hash tag must be
    preserved.
24. **Lua return codes are stable.** `-2` means cache miss, `-1` means sold
    out/insufficient stock, and non-negative means remaining stock.
25. **SHA reuse is intentional.** The typed `DefaultRedisScript<Long>` is a
    singleton Spring bean.
26. **Redis mechanics and business interpretation are split.**
    `StockDecrementLuaExecutor` owns mechanics, the Redis adapter implements the
    port, and `StockCounterService` interprets raw outcomes.
27. **The executor is decision-free; the Redis adapter owns transport
    classification.** Connection, timeout, and transport failures are
    indeterminate; deterministic failures fail closed.
28. **Application ports expose no infrastructure types.**
29. **StockCounterService must not bypass Product.** It loads Product and
    resolves the owned StockLevel before calling outbound decrement ports.
30. **Every recognized Redis result and every indeterminate failure selects
    durable authority exactly once.** There is no Redis retry.
31. **PostgreSQL exclusively decides success or sold out.** Redis decrement
    results never determine the client-visible outcome.
32. **Durable mutation belongs to Product.** `Product.decrementStock` owns
    validation, sold-out non-mutation, and StockLevel version advancement.
33. **Locking remains aggregate-root locking.** `findByIdForUpdate` uses
    `PESSIMISTIC_WRITE` on Product; there is no StockLevel repository.
34. **The durable transaction is proxy-visible.**
    `TransactionalStockDecrement` owns lock, mutation, managed update, flush,
    and revision retrieval; `PostgresStockDecrementAdapter` returns after
    commit.
35. **Redis I/O occurs outside the PostgreSQL transaction.** Projection
    synchronization starts only after the durable port returns.
36. **The mapper remains the sole domain/JPA translation boundary.** It applies
    the successful domain result to the managed StockLevel.
37. **The durable result includes the child revision.** Post-commit projection
    synchronization is fenced by the flushed StockLevel revision.
38. **Stock and revision keys share the SaleId hash tag.** The keys are
    `stock:{saleId}` and `stock:version:{saleId}`.
39. **Equal or newer durable revisions apply; older revisions are ignored.**
    This permits idempotent repair without stale overwrite.
40. **Missing and revisionless projections are distinct.** Missing stock is not
    recreated; revisionless stock is invalidated.
41. **Projection synchronization preserves the existing stock TTL.** It does
    not invent sale timing or pre-warm policy.
42. **Committed durable success wins over projection failure.** Unavailable or
    null synchronization warns but cannot change the returned durable result.
43. **There is no retry, compensation, legacy fallback, or request-time
    re-warming bridge.**
44. **Real infrastructure behavior is part of the gate.** PostgreSQL and Redis
    Testcontainers suites cover locking, concurrency, failover, and fencing.
45. **No unrelated refactoring is permitted.** SaleService was not changed by
    the implementation slice.
46. **Excluded capabilities remain excluded.** No pre-warm use case, REST,
    Kafka, Reservation/Week 4 work, release, or reconciliation was added.

---

# Important Invariants

Future implementation must preserve every invariant below unless an explicit
architecture decision changes it.

## Domain invariants

- `StockCount` can never be negative.
- Product total stock can be zero but never negative.
- A StockLevel allocation must be strictly positive.
- The sum of all Product StockLevel allocations can never exceed Product total
  stock.
- A Product can contain at most one StockLevel for a SaleId.
- Every StockLevel inside Product must have the same ProductId as its owner.
- `0 <= StockLevel.currentStock <= StockLevel.totalAllocated`.
- Product and StockLevel versions can never be negative.
- Product collection access must return immutable snapshots.
- Failed allocation must add no child and increment no version.
- Insufficient durable stock must not mutate the owned StockLevel.
- Successful durable decrement must reduce current stock by exactly the
  positive requested quantity and advance the StockLevel domain version.
- Stock arithmetic must retain overflow and underflow checks.
- SaleId remains an opaque cross-context identifier.

## Persistence and database invariants

- Domain classes remain free of JPA annotations.
- Domain and JPA models remain separate.
- StockLevels are persisted only through Product.
- `stock_levels.product_id` is mandatory and foreign-key constrained.
- `(product_id, sale_id)` remains unique in Java persistence metadata and SQL.
- `current_stock` never exceeds `total_allocated` in SQL.
- Product and StockLevel optimistic version columns remain mapped and
  non-negative.
- No cross-database Sale foreign key is introduced.
- Hibernate remains in validation mode; Flyway owns schema evolution.
- No Reservation, audit, release, reconciliation, Kafka/outbox, or Week 4 table
  may be added under the current V1 approval.

## Redis and application invariants

- Atomic decrement remains a Redis Lua operation.
- The stock key remains exactly `stock:{saleId}`.
- The projection revision key remains exactly `stock:version:{saleId}`.
- The script return contract remains `-2`, `-1`, or non-negative.
- Executors do not interpret business outcomes; the decrement adapter alone
  classifies deterministic versus indeterminate Redis failures.
- `StockCounterService` resolves allocation through Product before decrement.
- One service call invokes `StockDecrementPort` at most once and invokes
  `DurableStockDecrementPort` exactly once for every recognized Redis result or
  indeterminate Redis failure.
- Unknown negative, null, and out-of-`int` port values are rejected.
- PostgreSQL exclusively determines the returned `Decremented` or `SoldOut`
  result.
- PostgreSQL holds the Product `PESSIMISTIC_WRITE` lock through the
  authoritative domain decrement and managed StockLevel flush.
- The durable port returns only after commit and carries the flushed child
  revision.
- Projection synchronization occurs after commit and uses same-slot stock and
  revision keys.
- Equal/newer revisions apply; strictly older revisions cannot overwrite stock.
- Revisionless stock is invalidated, missing stock is not recreated, and an
  existing stock TTL is preserved.
- Projection unavailability or a null sync result cannot change a committed
  durable result into a failed result.
- No legacy fallback or request-time re-warming compatibility bridge remains.
- Stock must never become negative under concurrency or infrastructure failure.

---

# Verification

## Build and test commands executed

The following are all Gradle project, build, property, and test commands used
during the approved implementation slices. Repeated commands are listed with
the slice in which each run occurred because the passing test count changed.
Entries through Redis re-warming are historical evidence for their named
commits; the durable-authority gate is the current baseline.

### Skeleton

```bash
./gradlew projects
```

The first attempt was blocked because the managed sandbox could not create the
Gradle wrapper cache lock file. The same command was rerun with approved cache
access.

```bash
./gradlew projects && ./gradlew :services:inventory-service:build
./gradlew :services:inventory-service:properties | rg '^(sourceCompatibility|targetCompatibility):'
```

Results:

- Multi-module project discovery succeeded in 18 seconds.
- Initial InventoryService build succeeded in 1 minute 5 seconds.
- Five tasks executed.
- Tests correctly reported `NO-SOURCE`.
- Source and target compatibility both resolved to Java 21.

### Domain

```bash
./gradlew :services:inventory-service:test --tests 'com.flashsale.inventory.domain.*'
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
./gradlew :services:inventory-service:cleanTest :services:inventory-service:test
```

Results:

- Focused domain run succeeded in 17 seconds.
- Full domain-slice build succeeded in 11 seconds.
- Final domain rerun succeeded in 9 seconds.
- 27 tests passed.

### Persistence

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result: succeeded in 22 seconds with 33 passing tests.

### Flyway migration

```bash
./gradlew :services:inventory-service:clean :services:inventory-service:build
```

Result: succeeded in 18 seconds with 33 passing tests. The V1 migration was
packaged in the executable JAR.

### Redis Lua integration

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result: succeeded in 18 seconds with 39 passing tests.

### Redis adapter

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result: succeeded in 23 seconds with 44 passing tests.

### StockCounterService

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result: succeeded in 19 seconds with 55 passing tests.

### Handoff verification rerun

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

The first restricted-sandbox attempt could not create the Gradle wrapper cache
`.lck` file under the user Gradle directory. The identical command was rerun
with approved access and succeeded in 22 seconds. Test result XML confirmed 55
tests, 0 failures, 0 errors, and 0 skipped.

### PostgreSQL fallback

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

The first restricted-sandbox attempt could not create the Gradle wrapper cache
lock under the user Gradle directory. The command was rerun with approved cache
access. After the final mapping-boundary review, the complete clean build was
run again against the final source state.

Results:

- `BUILD SUCCESSFUL` in 19 seconds.
- 67 tests passed, 0 failed, 0 errors, and 0 skipped.
- `git diff --check` passed before commit.
- Scope checks confirmed no changes to documentation, SaleService, Inventory
  migrations/resources, Redis Lua scripts, REST, Kafka, Reservation, retry,
  pre-warm, or Redis re-warming code in implementation commit `9bb3ad7`.
- Domain framework-import and application-to-infrastructure import scans
  returned no matches.

### Redis re-warming

Focused verification:

```bash
./gradlew :services:inventory-service:test \
  --tests 'com.flashsale.inventory.application.StockCounterServiceTest' \
  --tests 'com.flashsale.inventory.infra.redis.RedisStockRewarmAdapterTest'
```

Result: `BUILD SUCCESSFUL` in 12 seconds.

Final required verification:

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Results:

- `BUILD SUCCESSFUL` in 25 seconds.
- 71 tests passed, 0 failed, 0 errors, and 0 skipped.
- `git diff --check` passed.
- Domain and application-port framework/infrastructure import scans returned
  no matches.
- Application-to-infrastructure import scans returned no matches.
- Domain, persistence, Flyway, Lua, Gradle, configuration, and SaleService
  files were unchanged.
- Scope scans found no REST, Kafka, Reservation, retry, pre-warm, release, or
  reconciliation implementation.
- The executable JAR contains `StockRewarmPort`,
  `StockRewarmUnavailableException`, and `RedisStockRewarmAdapter`.

### Durable-authority correctness gate

Inventory verification:

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result: `BUILD SUCCESSFUL` in 41 seconds; 126 tests passed with zero failures,
errors, or skips. The 17 runnable test classes comprise 105 unit tests and 21
PostgreSQL/Redis Testcontainers tests.

Complete repository verification:

```bash
./gradlew clean build
```

Result: `BUILD SUCCESSFUL` in 1 minute 6 seconds. InventoryService passed all
126 tests and SaleService passed all 16 tests, with zero failures, errors, or
skips. Domain framework-import, application dependency-direction, prohibited
scope, legacy bridge/fallback/re-warm, and unintegrated capability scans were
clean at implementation commit `bca1ff1`.

### Property-Based Stock Correctness Tests

Focused property verification:

```bash
./gradlew :services:inventory-service:test \
  --tests 'com.flashsale.inventory.domain.aggregate.ProductStockCorrectnessPropertyTest'
```

Result: `BUILD SUCCESSFUL` in 23 seconds. Five jqwik properties ran 1,000
generated examples each, for 5,000 passing generated checks. They verify exact
successful decrement, non-negative stock, insufficient-stock non-mutation,
exact depletion, repeated-operation arithmetic, boundary values, and
overflow-safe generated quantities.

Inventory verification:

```bash
./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
```

Result: `BUILD SUCCESSFUL` in 30 seconds; 131 tests passed with zero failures,
errors, or skips.

Complete repository verification:

```bash
./gradlew clean build
```

Result: `BUILD SUCCESSFUL` in 59 seconds. InventoryService passed all 131 tests
and SaleService passed all 16 tests, with zero failures, errors, or skips.
Dependency inspection confirmed jqwik on Inventory's test runtime classpath
only and absent from its production runtime classpath. `git diff --check`, the
production-code diff check, and the existing-test diff check passed. The slice
is commit `f12d67d`, already pushed to `origin/main`.

## Latest successful build

```text
Command: ./gradlew :services:inventory-service:test && ./gradlew build
Result:  BUILD SUCCESSFUL (SESSION-009, working tree, pre-warm uncommitted)
Time:    23 seconds
Tests:   Inventory 163; Sale 16; 0 failed, 0 errors, 0 skipped
```

## Passing test inventory

| Inventory test category | Runnable classes | Passing tests |
|---|---:|---:|
| Unit/property | 19 | 135 |
| PostgreSQL/Redis Testcontainers integration | 4 | 28 |
| **Inventory total** | **23** | **163** |

The complete repository build also runs 16 passing SaleService tests.

> **Note:** 4 new test classes and modified `RedisScriptConfigurationTest` are
> untracked/modified. Counts reflect the current working-tree build.

Non-failing warnings observed:

- Gradle reported deprecated features that will be incompatible with Gradle
  9.0.
- The test JVM reported a class-data-sharing limitation after Mockito appended
  to the bootstrap classpath.

## Known unresolved technical assumptions

- StockCounterService currently loads Product from PostgreSQL before every
  Redis decrement. This frozen ownership check and the authoritative
  PostgreSQL decrement add database latency; do not remove either without a new
  architecture decision.
- Product-root `PESSIMISTIC_WRITE` intentionally serializes decrements for the
  same Product, including distinct SaleIds under that Product.
- Revisionless Redis stock is invalidated on synchronization. Pre-warm must
  initialize the compatible stock revision key or the first request can remove
  that stock projection.
- Projection behavior is verified against standalone Redis 7.2; cluster routing
  still depends on preserving the shared SaleId hash tag in both keys.
- A response can still be lost after PostgreSQL commit. Retry/idempotency at the
  API or order boundary is not part of this slice.
- The separate pre-warm, release, and reconcile Lua resources do not represent
  integrated use cases.
- SaleService still needs an approved migration to the Inventory decrement
  contract; no cross-service API was added here.
- Legacy schema documents describe fields and tables that are absent from the
  approved minimal JPA/Flyway model.

---

# Remaining Week 3 Tasks

Only unfinished work appears in this section.

## 1. Commit the pre-warm implementation ← START HERE

The pre-warm use case implementation (SESSION-009) is **complete and approved**
but not yet committed. The working tree contains 12 untracked files and 3
modified files. All files are reviewed, build-verified (163 tests passing), and
ready to commit.

Files to stage:

New production (7 untracked):
- `src/main/java/com/flashsale/inventory/application/PreWarmStockResult.java`
- `src/main/java/com/flashsale/inventory/application/PreWarmStockUseCase.java`
- `src/main/java/com/flashsale/inventory/application/port/StockPreWarmPort.java`
- `src/main/java/com/flashsale/inventory/application/port/StockPreWarmUnavailableException.java`
- `src/main/java/com/flashsale/inventory/infra/redis/StockPreWarmLuaExecutor.java`
- `src/main/java/com/flashsale/inventory/infra/redis/RedisStockPreWarmAdapter.java`
- `src/main/java/com/flashsale/inventory/infra/config/InventoryConfiguration.java`

New test (4 untracked):
- `src/test/java/com/flashsale/inventory/application/PreWarmStockUseCaseTest.java`
- `src/test/java/com/flashsale/inventory/infra/redis/StockPreWarmLuaExecutorTest.java`
- `src/test/java/com/flashsale/inventory/infra/redis/RedisStockPreWarmAdapterTest.java`
- `src/test/java/com/flashsale/inventory/integration/StockPreWarmIntegrationTest.java`

Modified (3):
- `src/main/resources/lua/stock-prewarm.lua` (full rewrite)
- `src/main/java/com/flashsale/inventory/infra/config/RedisScriptConfiguration.java`
- `src/test/java/com/flashsale/inventory/infra/config/RedisScriptConfigurationTest.java`

Suggested commit message:

```
feat(inventory): implement pre-warm use case per ADR-020 Revision 2

- Revision-fenced stock-prewarm.lua (both keys, all §13 table rows)
- PreWarmStockUseCase: timing validation, snapshot load, TTL derivation
- StockPreWarmPort / RedisStockPreWarmAdapter / StockPreWarmLuaExecutor
- InventoryConfiguration: Clock bean
- 32 new tests (9 unit use-case, 9 adapter, 6 executor, 7 integration, 1 script)
- saleEnd > saleStart validation (ADR-020 §10 terminal failure)
163 tests passing; 0 failed
```

## 2. Regression maintenance

- Retain all 163 Inventory tests unless an approved contract intentionally
  evolves.
- Extend the real-infrastructure suites alongside any further slice so
  revision fencing, TTL preservation, and zero-oversell behavior remain
  protected.
- Run the full Inventory module build after every slice.

## 3. Week 3 documentation reconciliation

- Update `context/PROJECT_TRUTH.md` to current repository reality.
- Record which legacy Build Plan and Database Schema statements are obsolete.
- Update `context/REPOSITORY_INDEX.md` for InventoryService files/directories.
- Mark Week 3 complete only after the pre-warm commit is pushed and the working
  and canonical documentation are reconciled.

Kafka integration, Inventory GET endpoints, Reservation/Week 4 work, release,
and reconciliation are not remaining Week 3 tasks and must not be introduced.

---

# Rules for the Next Session

1. Read this handoff and the documents in the stated order before changing
   code.
2. Verify `pwd`, branch, HEAD, and `git status` before inspecting or editing.
3. Treat current repository source as implementation reality; do not implement
   stale planned fields or tables.
4. Implement exactly one approved slice at a time.
5. Stop after that slice, explain every change, show verification results, and
   wait for approval.
6. Never refactor or rewrite a completed approved slice unless the new task
   explicitly requires it.
7. Never change an approved architecture decision silently.
8. Preserve Java 21, Spring Boot 3.3.4, the existing Gradle structure, and the
   current package layout.
9. Keep the domain framework-free.
10. Keep business invariants in Product, StockLevel, or StockCount whenever the
    domain can own them.
11. Application services may depend only on domain types and application
    ports; never import infrastructure.
12. Infrastructure adapters may implement ports but may not invent business
    decisions.
13. Never bypass Product ownership or introduce an unrestricted StockLevel
    repository.
14. Keep JPA entities separate from domain classes.
15. Keep all domain/JPA translation in the mapper boundary.
16. Use Flyway for schema changes and keep Hibernate in validate mode.
17. Preserve the approved Lua script contracts and Redis key format.
18. Never replace atomic Lua decrement with a client-side read/check/write
    sequence.
19. Preserve exactly one PostgreSQL authoritative decrement for every
    recognized Redis result or indeterminate Redis failure.
20. Preserve revision-fenced, post-commit projection synchronization; do not
    restore the legacy fallback/re-warm compatibility bridge.
21. Do not recreate missing stock during projection sync; pre-warm owns stock
    creation and TTL initialization under a separately approved contract.
22. Do not modify SaleService unless the approved slice makes it strictly
    necessary.
23. Do not add Kafka, REST endpoints, DTOs, Reservation/Week 4 work, release,
    or reconciliation under the current Week 3 scope.
24. Add focused tests for the slice being implemented.
25. Run at least:

    ```bash
    ./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
    ```

26. Report total passed, failed, and skipped tests.
27. Run scope checks that prove excluded layers and files were not changed.
28. Preserve unrelated user changes in the working tree.
29. Do not mark Week 3 complete until every unfinished task in this handoff is
    approved and verified.
30. Update the append-only session log at the end of the next working session.
