# Flash Sale Platform — Engineering Handoff

**Handoff date:** 2026-07-24

**Current milestone:** Week 3 — InventoryService

**Week 3 status:** In progress

**Branch:** `main`

**Implementation HEAD:** `9bb3ad7` (`feat(inventory): implement PostgreSQL fallback adapter`)

**Audience:** The senior engineer or Codex session continuing Week 3 development

This is the entry document for the next development session. It records the
approved repository state through the PostgreSQL fallback slice. Do not infer
that Week 3 is complete: Redis re-warming, pre-warming, live/concurrent
correctness tests, and the remaining regression suites have not been implemented.

> **Documentation drift warning:** `context/PROJECT_TRUTH.md` and
> `context/REPOSITORY_INDEX.md` still contain stale implementation status.
> Until they are reconciled, current source code, this handoff,
> `context/CURRENT_STATE.md`, and `SESSION-003` plus `SESSION-004` in
> `context/SESSION_LOG.md` are the verified implementation evidence. Do not
> copy stale planned fields or structures into code.

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
  -> stock-decrement.lua

On -2 cache miss or translated Redis connection failure:
StockCounterService
  -> StockFallbackPort
  -> PostgresStockFallbackAdapter (@Transactional)
  -> SpringDataProductRepository.findByIdForUpdate (PESSIMISTIC_WRITE)
  -> Product.decrementStock
  -> ProductPersistenceMapper.applyCurrentStock
  -> managed StockLevel flush
```

Redis success and sold-out outcomes do not touch PostgreSQL. A Redis cache miss
or translated connection failure invokes the fallback port once. Redis
re-warming remains unfinished.

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
   the PostgreSQL fallback slice.
4. `docs/architecture/Build-Plan.md` — milestone intent and sequencing; treat
   unapproved legacy details as plans, not implementation requirements.
5. `context/SESSION_LOG.md` — read `SESSION-003` and `SESSION-004` for the
   exhaustive implementation, fallback, verification, and decision records.
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

InventoryService currently contains:

- 22 production Java types.
- 11 test classes.
- 67 passing unit tests.
- One integrated Lua script: `stock-decrement.lua`.
- Two Flyway-managed tables: `products` and `stock_levels`.
- No REST endpoints, Kafka code, re-warming, pre-warm use case, Reservation
  work, release integration, or reconciliation integration.

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
- Insufficient fallback stock does not mutate the StockLevel.
- A successful fallback decrement replaces the immutable owned StockLevel,
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

The existing approved resource
`src/main/resources/lua/stock-decrement.lua` is loaded without copying script
logic into Java.

Script contract:

- `KEYS[1] = stock:{saleId}`.
- `ARGV[1] = quantity`.
- Return `-2` for cache miss.
- Return `-1` for sold out or insufficient stock.
- Return a non-negative remaining stock count on success.
- Check and `DECRBY` execute atomically on the Redis server.

`RedisScriptConfiguration` creates a singleton
`DefaultRedisScript<Long>` from the classpath resource. The approved script
SHA-1 is:

```text
2dab8322003da880c4aa8a4f55ca9aefaadc0215
```

`StockDecrementLuaExecutor` owns Redis key construction, argument
serialization, `StringRedisTemplate.execute`, and raw nullable result
propagation. It does not interpret business outcomes.

No live Redis execution has been performed yet; loading, SHA stability, key
formatting, arguments, and template wiring are unit-tested.

## ✔ Redis Adapter

`StockDecrementPort` is an infrastructure-neutral outbound application port:

```text
decrement(SaleId, int) -> Long
```

It exposes no Redis, Lua, Spring, key, or serialization type.

`RedisStockDecrementAdapter` implements the port by delegating exactly once to
`StockDecrementLuaExecutor`. It preserves `-2`, `-1`, non-negative values, and
`null` without business interpretation. It translates only
`RedisConnectionFailureException` into the application-owned
`StockDecrementUnavailableException`; all result interpretation remains in the
application service.

## ✔ StockCounterService

`StockCounterService` is the current decrement use-case orchestrator.

Current behavior:

1. Reject null ProductId or SaleId.
2. Load Product through the application `ProductRepository` port.
3. Resolve the sale allocation through `Product.stockLevelFor`; never query a
   StockLevel independently.
4. Validate that quantity is positive and no greater than the StockLevel's
   total allocation by using `StockCount.canDecrement`.
5. Invoke `StockDecrementPort` exactly once.
6. On `-2` cache miss or `StockDecrementUnavailableException`, invoke
   `StockFallbackPort` exactly once.
7. Map an empty fallback result or Redis `-1` to
   `StockDecrementResult.SoldOut`.
8. Map a non-negative Redis result or present fallback result to
   `StockDecrementResult.Decremented(StockCount)`.
9. Reject `null`, unknown negative values, and values outside Java `int`
   range as illegal infrastructure results.

Current error behavior:

- Missing Product or StockLevel: `NoSuchElementException`.
- Invalid quantity: `IllegalArgumentException`.
- Invalid port output: `IllegalStateException`.
- Sold out and success remain explicit sealed application outcomes; cache miss
  is resolved internally through the fallback.

The service does not retry, re-warm, or pre-warm Redis. A successful Redis
decrement still performs no PostgreSQL write. There is no stable external/API
error contract.

## ✔ PostgreSQL Fallback

`StockFallbackPort` is the infrastructure-neutral application boundary for one
durable decrement:

```text
decrement(ProductId, SaleId, int) -> Optional<StockCount>
```

An empty result means the locked durable StockLevel has insufficient stock. A
present result contains the durable stock remaining after one successful
decrement.

`PostgresStockFallbackAdapter` implements the port in one `@Transactional`
operation:

1. Lock the Product aggregate root through
   `SpringDataProductRepository.findByIdForUpdate` with
   `PESSIMISTIC_WRITE`.
2. Map the complete managed Product/StockLevel tree to the domain.
3. Invoke `Product.decrementStock`.
4. Return sold out without mutation or flush when durable stock is
   insufficient.
5. Apply successful current-stock state through
   `ProductPersistenceMapper.applyCurrentStock`.
6. Flush the managed owned StockLevel before returning the remaining stock.

There is no independent StockLevel repository, schema change, retry, Redis
re-warming, pre-warm, REST, Kafka, Reservation, audit, release, or
reconciliation work in this slice.

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

## Resource paths

- `services/inventory-service/src/main/resources/db/migration` — Inventory
  Flyway migrations.
- `services/inventory-service/src/main/resources/lua` — approved Redis Lua
  resources. Only `stock-decrement.lua` is integrated.

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
- **Responsibility:** Infrastructure-neutral signal that the primary stock
  counter cannot be reached.
- **Dependencies:** Java `RuntimeException` only.
- **Why it exists:** Lets application orchestration select the approved durable
  fallback without importing Redis exception types.

### `StockFallbackPort`

- **Package:** `com.flashsale.inventory.application.port`
- **Responsibility:** Defines one authoritative durable decrement using
  ProductId, SaleId, quantity, and an optional remaining StockCount.
- **Dependencies:** Domain typed IDs, `StockCount`, and Java `Optional`.
- **Why it exists:** Keeps PostgreSQL and transaction mechanics behind an
  application-owned boundary.

### `StockCounterService`

- **Package:** `com.flashsale.inventory.application`
- **Responsibility:** Orchestrates one validated decrement attempt through
  Product ownership and application ports.
- **Dependencies:** Application `ProductRepository`, `StockDecrementPort`,
  `StockFallbackPort`, and `StockDecrementUnavailableException`; domain
  `Product`, `StockLevel`, `ProductId`, `SaleId`, and `StockCount`; Spring
  `@Service`.
- **Why it exists:** Result interpretation and use-case sequencing belong
  outside both the domain model and Redis adapter.

### `StockDecrementResult`

- **Package:** `com.flashsale.inventory.application`
- **Responsibility:** Sealed application outcome for decrement.
- **Dependencies:** Domain `StockCount` and Java `Objects`.
- **Why it exists:** Makes success, sold out, and cache miss explicit without
  leaking Redis numeric codes to callers.
- **Nested result records:**
  - `Decremented(StockCount remainingStock)` — successful decrement.
  - `SoldOut()` — Redis reported insufficient/no stock.
  - `CacheMiss()` — retained in the established result vocabulary; current
    orchestration resolves cache misses before returning.

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
  fallback current-stock state to the matching managed StockLevel.
- **Dependencies:** All Inventory domain types, both JPA entity types, and
  Spring `@Component`.
- **Why it exists:** Keeps all persistence translation in one explicit boundary
  and ensures invalid persisted state is revalidated by the domain.

### `SpringDataProductRepository`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Spring Data CRUD repository for `ProductJpaEntity` with an
  entity-graph `findById` that fetches owned StockLevels and a
  `PESSIMISTIC_WRITE` Product-root lookup for fallback.
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

### `PostgresStockFallbackAdapter`

- **Package:** `com.flashsale.inventory.infra.persistence`
- **Responsibility:** Implements `StockFallbackPort` by locking the Product
  aggregate root, invoking the Product-owned decrement, applying state through
  the mapper, and flushing the managed StockLevel in one transaction.
- **Dependencies:** Application `StockFallbackPort`, Inventory domain types,
  Spring Data repository/mapper, and Spring transaction/repository annotations.
- **Why it exists:** Provides the approved PostgreSQL fallback without exposing
  a child repository or moving business rules into persistence.

### `RedisScriptConfiguration`

- **Package:** `com.flashsale.inventory.infra.config`
- **Responsibility:** Loads `lua/stock-decrement.lua` as a singleton
  `DefaultRedisScript<Long>` bean.
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

### `RedisStockDecrementAdapter`

- **Package:** `com.flashsale.inventory.infra.redis`
- **Responsibility:** Implements `StockDecrementPort`, delegates to
  `StockDecrementLuaExecutor`, preserves raw results, and translates Redis
  connection failure to the application-owned unavailable signal.
- **Dependencies:** Application `StockDecrementPort` and
  `StockDecrementUnavailableException`, domain `SaleId`, Redis exception type,
  `StockDecrementLuaExecutor`, and Spring `@Component`.
- **Why it exists:** Provides the hexagonal adapter boundary while keeping
  Redis exception types out of application orchestration.

## Test classes

### `ProductTest`

- **Responsibility:** Verifies Product creation, allocations, allocation
  ceiling, duplicate Sale rejection, ownership, immutable snapshots, failed
  allocation atomicity, reconstitution, successful fallback decrement,
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

- **Responsibility:** Verifies classpath loading, approved script content,
  `Long` result type, exact SHA, singleton bean identity, and SHA reuse.
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

- **Responsibility:** Verifies exact SaleId/quantity delegation and unchanged
  propagation of `-2`, `-1`, zero, positive, and null executor results, plus
  translation of Redis connection failure.
- **Dependencies:** JUnit Jupiter, Mockito, `StockDecrementPort`,
  `StockDecrementLuaExecutor`, and `SaleId`.
- **Why it exists:** Ensures the adapter remains a decision-free delegation
  boundary.

### `StockCounterServiceTest`

- **Responsibility:** Verifies Redis success/sold out, cache-miss and
  unavailable-counter fallback, fallback success/sold out/null rejection,
  missing Product/StockLevel, invalid quantities/results, no direct repository
  save, and exactly one invocation of each selected port.
- **Dependencies:** JUnit Jupiter, Mockito, application ports and results, and
  Inventory domain types.
- **Why it exists:** Protects the approved use-case orchestration and prevents
  retries, double port invocation, or re-warming from entering the slice.

### `PostgresStockFallbackAdapterTest`

- **Responsibility:** Verifies locked Product lookup, Product-owned decrement,
  managed StockLevel update/flush, sold-out non-mutation, missing Product
  handling, `PESSIMISTIC_WRITE`, and transactional fallback declaration.
- **Dependencies:** JUnit Jupiter, Mockito, Inventory domain/JPA types, Spring
  Data lock metadata, and Spring transaction metadata.
- **Why it exists:** Protects the fallback transaction and aggregate-locking
  contract without claiming live PostgreSQL or concurrent verification.

---

# Modified Files

This section lists every file created or changed by the eight approved
implementation slices, the session log updates, and this handoff.

## Existing repository files changed

| File | Why it changed |
|---|---|
| `settings.gradle` | Added `include 'services:inventory-service'` to the multi-module build. |
| `services/inventory-service/src/main/java/com/flashsale/inventory/infra/persistence/ProductRepository.java` | Initially added as the JPA aggregate adapter; later updated only to implement the application ProductRepository port and add `@Override` markers. |
| `context/SESSION_LOG.md` | Appended `SESSION-003` and `SESSION-004`; previous history was preserved. |
| `context/CURRENT_STATE.md` | Updated the verified milestone snapshot through the PostgreSQL fallback slice. |
| `HANDOFF.md` | Updated this production handoff through the PostgreSQL fallback slice. |

## New skeleton/configuration files

| File | Why it exists |
|---|---|
| `services/inventory-service/build.gradle` | Defines the InventoryService Spring Boot module and its dependencies. |
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

## Approved resource used unchanged

`services/inventory-service/src/main/resources/lua/stock-decrement.lua` existed
before the Java integration and was loaded unchanged. It is not a modified file
from these slices. The pre-existing `stock-prewarm.lua`, `stock-release.lua`,
and `stock-reconcile.lua` resources remain unintegrated.

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
27. **The executor is decision-free; the Redis adapter translates only
    connectivity.** Raw nullable results pass through unchanged, while
    `RedisConnectionFailureException` becomes the application-owned unavailable
    signal.
28. **Application ports expose no infrastructure types.**
29. **StockCounterService must not bypass Product.** It loads Product and
    resolves the owned StockLevel before calling the decrement port.
30. **Cache miss and primary-counter unavailability select the fallback.** The
    service invokes `StockFallbackPort` once and performs no retry or re-warm.
31. **Current Redis success performs no JPA save.** PostgreSQL is not updated by
    the completed service slice.
32. **Error handling is layer-specific.** Expected decrement outcomes use the
    sealed result; missing domain state and invalid infrastructure results are
    exceptions until an API contract is separately approved.
33. **Tests are focused per slice.** Completed tests are unit tests; no live
    Redis/PostgreSQL behavior is claimed.
34. **No unrelated refactoring is permitted.** SaleService was not changed.
35. **Excluded capabilities remain excluded.** No Kafka, Inventory GET
    endpoint, Reservation/Week 4 work, release, or reconciliation was added.
36. **Fallback mutation belongs to Product.** `Product.decrementStock` owns
    current-stock validation, sold-out non-mutation, and StockLevel version
    advancement.
37. **Fallback locking is aggregate-root locking.**
    `findByIdForUpdate` uses `PESSIMISTIC_WRITE` on Product, serializing
    fallback decrements without exposing a StockLevel repository.
38. **Fallback is one transaction.** Lock, domain mutation, mapper application,
    managed StockLevel update, and flush occur inside
    `PostgresStockFallbackAdapter.decrement`.
39. **The mapper remains the sole domain/JPA translation boundary.**
    `ProductPersistenceMapper.applyCurrentStock` applies the successful domain
    result to the managed StockLevel.
40. **Fallback returns only success or sold out.** A present `StockCount` is
    success; an empty optional is insufficient durable stock.
41. **Redis success still does not update PostgreSQL.** The fallback is selected
    only for cache miss or the translated unavailable signal.
42. **No fallback side effects were added.** There is no re-warming, pre-warm,
    retry, audit/log table, REST, Kafka, Reservation, release, or
    reconciliation behavior.

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
- Insufficient fallback stock must not mutate the owned StockLevel.
- Successful fallback decrement must reduce current stock by exactly the
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
- The script return contract remains `-2`, `-1`, or non-negative.
- Executor and Redis adapter do not interpret numeric business results; the
  adapter translates only Redis connection failure.
- `StockCounterService` resolves allocation through Product before decrement.
- One service call invokes `StockDecrementPort` at most once.
- Unknown negative, null, and out-of-`int` port values are rejected.
- Cache miss or primary-counter unavailability invokes `StockFallbackPort`
  exactly once, with no retry or re-warm.
- A successful Redis decrement currently causes no ProductRepository save.
- PostgreSQL fallback holds the Product `PESSIMISTIC_WRITE` lock through the
  authoritative domain decrement and managed StockLevel flush.
- Stock must never become negative under concurrency or infrastructure failure.

---

# Verification

## Build and test commands executed

The following are all Gradle project, build, property, and test commands used
during the approved implementation slices. Repeated commands are listed with
the slice in which each run occurred because the passing test count changed.

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

## Latest successful build

```text
Command: ./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
Result:  BUILD SUCCESSFUL
Time:    19 seconds
Tests:   67 passed, 0 failed, 0 skipped
```

## Passing test inventory

| Test class | Passing tests |
|---|---:|
| `ProductTest` | 14 |
| `StockLevelTest` | 6 |
| `StockCountTest` | 7 |
| `TypedIdTest` | 4 |
| `ProductPersistenceMapperTest` | 3 |
| `ProductRepositoryTest` | 3 |
| `RedisScriptConfigurationTest` | 1 |
| `StockDecrementLuaExecutorTest` | 5 |
| `RedisStockDecrementAdapterTest` | 6 |
| `StockCounterServiceTest` | 14 |
| `PostgresStockFallbackAdapterTest` | 4 |
| **Total** | **67** |

All 67 are unit tests. There is no Testcontainers, live PostgreSQL, live Redis,
concurrent integration, or full application-context test yet.

Non-failing warnings observed:

- Gradle reported deprecated features that will be incompatible with Gradle
  9.0.
- The test JVM reported a class-data-sharing limitation after Mockito appended
  to the bootstrap classpath.

## Known unresolved technical assumptions

- Product manually increments its domain version on allocation while JPA maps
  that value with `@Version`. Unit mapping is verified, but detached-update
  semantics have not been proven against real Hibernate/PostgreSQL.
- StockCounterService currently loads Product from PostgreSQL before every
  Redis decrement. This preserves the approved aggregate check but conflicts
  with the stated Redis-only hot-path performance goal. Do not remove the load
  without an explicit architecture decision.
- Product now exposes the approved immutable-StockLevel replacement command
  for fallback, but its pessimistic/optimistic version interaction has not been
  verified against real Hibernate/PostgreSQL or concurrent transactions.
- No live Redis test has proved the Lua script against a real Redis server.
- `StockDecrementPort` intentionally returns nullable `Long`; executor and
  adapter pass numeric/null results through, the adapter translates Redis
  connection failure, and StockCounterService rejects null.
- Existing pre-warm, release, and reconcile Lua files are resources only. Their
  presence does not mean they are integrated.
- Legacy schema documents describe fields and tables that are absent from the
  approved minimal JPA/Flyway model.

---

# Remaining Week 3 Tasks

Only unfinished work appears in this section.

## 1. Redis re-warming

- After a successful authoritative PostgreSQL fallback, repopulate the Redis
  counter from the locked durable value.
- Define behavior when the database decrement succeeds but re-warming fails.
- Never guess stock and never overwrite a newer Redis value.
- Keep re-warming behind an application port and infrastructure adapter.

## 2. Pre-warm use case

- Integrate the existing `lua/stock-prewarm.lua` through configuration,
  executor, Redis-neutral port, and adapter.
- Add an application use case for pre-warming.
- Preserve same-slot keys `stock:{saleId}` and
  `stock:warmed:{saleId}`.
- Pass total stock and TTL.
- Preserve the script contract `1 = warmed`, `0 = already warmed`.
- Explicitly decide how Inventory obtains sale start/end data and how pre-warm
  is triggered. Kafka and new endpoints remain excluded unless separately
  approved.
- Define TTL as `saleEnd - now + 600 seconds` only after the required sale-end
  input contract is approved.

## 3. Property-based tests

- Add jqwik or the approved property-testing mechanism.
- Prove across generated quantities and starting stock that stock never becomes
  negative.
- Prove successful decrements reduce stock by exactly the requested positive
  quantity.
- Prove insufficient stock never changes the counter.
- Cover boundary values, integer limits, and repeated operations.

## 4. Failure tests

- Execute the decrement Lua script against real Redis.
- Verify cache miss, sold out, insufficient stock, zero transition, and
  successful quantities.
- Verify live Redis-unavailable behavior invokes PostgreSQL fallback.
- Verify database lock contention is serialized and never oversells.
- Verify database success plus Redis re-warm failure has an explicit safe
  result.
- Verify malformed/unexpected Redis results remain rejected.
- Verify Flyway plus Hibernate validation against a real Inventory database.
- Verify the unresolved Product/JPA optimistic version behavior.

## 5. Regression tests

- Retain all 67 current unit tests unchanged unless an approved contract
  intentionally evolves.
- Add integration coverage for Product aggregate persistence with owned
  StockLevels.
- Add concurrent Redis decrement coverage proving zero oversell.
- Add concurrent PostgreSQL fallback coverage proving zero oversell.
- Add regression coverage proving one authoritative decrement when Redis
  fails before, during, or after execution.
- Run the full Inventory module build after every slice.

## 6. Week 3 documentation reconciliation

- Update `context/PROJECT_TRUTH.md` to current repository reality.
- Update `context/CURRENT_STATE.md` at the correct milestone boundary.
- Record which legacy Build Plan and Database Schema statements are obsolete.
- Update `context/REPOSITORY_INDEX.md` for InventoryService files/directories.
- Mark Week 3 complete only after re-warming, pre-warm, and correctness tests
  pass.

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
19. Any fallback must guarantee exactly one authoritative decrement.
20. Do not modify SaleService unless the approved slice makes it strictly
    necessary.
21. Do not add Kafka, REST endpoints, DTOs, Reservation/Week 4 work, release,
    or reconciliation under the current Week 3 scope.
22. Add focused tests for the slice being implemented.
23. Run at least:

    ```bash
    ./gradlew :services:inventory-service:cleanTest :services:inventory-service:build
    ```

24. Report total passed, failed, and skipped tests.
25. Run scope checks that prove excluded layers and files were not changed.
26. Preserve unrelated user changes in the working tree.
27. Do not mark Week 3 complete until every unfinished task in this handoff is
    approved and verified.
28. Update the append-only session log at the end of the next working session.
