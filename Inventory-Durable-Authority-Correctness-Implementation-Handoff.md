# Inventory Durable-Authority Correctness Implementation Handoff

Revision: 2

Status: Authoritative implementation contract for an AI engineer coding in a fresh context window.

Module root: `services/inventory-service`

## 1. Mission

Implement the approved Inventory durable-authority correctness gate only.

## 2. Architectural Goal

Canonical flow:

`Product validation → one Redis Lua attempt → one PostgreSQL durable decrement → commit → revision-fenced Redis synchronization → PostgreSQL-derived result`

PostgreSQL is authoritative. Redis is an atomic projection/admission mechanism.

## 3. Scope

- Confirm every recognized Redis outcome against PostgreSQL.
- Acknowledge decrement only after PostgreSQL commit.
- Replace fallback-only persistence with authoritative durable decrement.
- Replace `setIfAbsent` re-warming with revision-fenced synchronization.
- Add real PostgreSQL/Redis Testcontainers tests.
- Preserve Product ownership, existing decrement Lua, and current Product lookup.

## 4. Explicit Out-of-Scope

- Pre-warm scheduling, sale timing, TTL calculation.
- Reservation, idempotency, expiry, release, reconciliation.
- REST, DTOs, Kafka, events, outbox, retries, DLQ.
- Circuit breakers and performance optimization.
- Cross-request idempotency.
- SaleService and its migration defect.
- Documentation reconciliation.
- Lock-granularity redesign.
- Redis Cluster topology testing.

## 5. Architecture Decisions

- Every recognized Redis outcome and every indeterminate Redis transport failure invokes PostgreSQL exactly once.
- PostgreSQL exclusively determines returned `Decremented`/`SoldOut`.
- No distributed transaction.
- Redis synchronization occurs only after PostgreSQL commit.
- Synchronization failure preserves the durable result.
- Redis/PostgreSQL disagreement emits a warning.
- No release compensation.
- StockLevel persisted version is the Redis projection revision.
- No domain production changes or schema migration.

## 6. Domain Invariants

- `Product` owns StockLevel.
- One StockLevel per Product/Sale.
- No independent StockLevel repository or mutation.
- Quantity is positive.
- StockCount never becomes negative.
- Current stock never exceeds allocation.
- Insufficient durable stock causes no mutation/version advance.
- Successful durable decrement reduces stock exactly once.
- Successful mutation advances StockLevel version only.
- Product version remains unchanged.
- Every acknowledged decrement maps to one committed durable decrement.

## 7. Transaction Rules

- `StockCounterService` is non-transactional.
- Redis decrement executes outside PostgreSQL transactions.
- `TransactionalStockDecrement` owns one transaction.
- Product lock, mapping, domain mutation, managed-state update, flush, and revision retrieval occur inside it.
- Commit completes before the durable port returns.
- Projection synchronization executes after commit.
- No transaction spans Redis and PostgreSQL.
- Commit failure cannot return success.

## 8. Concurrency Rules

- Redis Lua serializes Redis counter operations.
- PostgreSQL `PESSIMISTIC_WRITE` locks the Product root.
- Product-root locking may serialize different sales for one Product.
- Out-of-order projection writes are fenced by StockLevel revision.
- Strictly older revisions cannot overwrite current or newer revisions.
- Equal revisions idempotently reapply the same authoritative durable state.
- Concurrent cache misses serialize through PostgreSQL and produce correct durable results; Redis remains missing until pre-warm.
- Ambiguous Redis execution, including indeterminate transport failure, still causes one durable invocation.
- Retried requests after committed response loss may decrement again; idempotency remains out of scope.

## 9. Redis Rules

Existing `stock-decrement.lua` remains unchanged.

Redis decrement results:

- `-2`: cache miss; invoke durable decrement.
- `-1`: projected sold-out; invoke durable decrement.
- Non-negative: projected success; invoke durable decrement.
- Connection loss, command timeout, or another indeterminate transport failure: invoke durable decrement.
- Deterministic script, data, or serialization failure: fail closed; do not invoke durable decrement.
- Null, unknown negative, or out-of-range: fail closed; no acknowledged success.

Projection keys:

- `stock:{saleId}`
- `stock:version:{saleId}`

Synchronization:

- Synchronize durable success and durable insufficiency.
- Missing stock key: delete orphan revision key; return `MISSING`; do not recreate.
- Missing revision with existing stock: treat the stock as untrusted; atomically delete the stock and revision keys; return `MISSING`.
- Strictly newer revision: update stock and revision; return `APPLIED`.
- Equal revision: idempotently update stock and revision from the same authoritative durable state; return `APPLIED`.
- Strictly older revision: no update; return `STALE_IGNORED`.
- Preserve stock TTL on every `APPLIED` synchronization.
- When applying synchronization, set the revision key to the stock key's same absolute expiration; keep the revision key persistent when the stock key is persistent.
- When updating a trusted existing stock key, never create, extend, remove, or otherwise alter its TTL; invalidation deletes the entire untrusted revisionless key.
- Invalid stored stock/revision is an infrastructure failure.
- Both keys must share the Redis Cluster hash tag.

## 10. PostgreSQL Rules

- `stock_levels.current_stock` is authoritative.
- Lock Product before durable evaluation.
- Insufficient result returns locked stock/revision without mutation.
- Successful result updates only the managed StockLevel.
- Flush before reading its updated revision.
- Return committed stock and revision.
- Database/transaction/commit failure becomes `DurableStockUnavailableException`.
- No database migration or schema change.

## 11. Required Ports

`DurableStockDecrementPort`

- `decrement(ProductId, SaleId, quantity)`
- Returns:
  - `DurableStockDecrementResult.Decremented(remainingStock, revision)`
  - `DurableStockDecrementResult.Insufficient(currentStock, revision)`

`StockProjectionSyncPort`

- `synchronize(SaleId, durableStock, durableRevision)`
- Returns `APPLIED`, `STALE_IGNORED`, or `MISSING`.

Ports expose only domain, application, and JDK types.

Unchanged:

- `ProductRepository`
- `StockDecrementPort`
- `StockDecrementUnavailableException`

## 12. Required Adapters

`PostgresStockDecrementAdapter`

- Implements durable port.
- Calls separately proxied transactional component.
- Translates database and commit-time failures.

`TransactionalStockDecrement`

- Locks, maps, mutates, flushes, retrieves revision.

`RedisStockProjectionSyncAdapter`

- Implements synchronization port.
- Maps script result codes.
- Translates Redis failures.

`StockProjectionSyncLuaExecutor`

- Constructs both keys.
- Serializes stock/revision.
- Executes Lua.
- Returns raw script result.

`RedisStockDecrementAdapter`

- Preserves the existing decrement port and Lua executor contracts.
- Translates connection loss, command timeout, and other indeterminate transport failures to `StockDecrementUnavailableException`.
- Allows deterministic script, data, serialization, and result-contract failures to remain fail-closed.

## 13. Files To Create

- `src/main/java/com/flashsale/inventory/application/DurableStockDecrementResult.java`
- `src/main/java/com/flashsale/inventory/application/StockProjectionSyncResult.java`
- `src/main/java/com/flashsale/inventory/application/port/DurableStockUnavailableException.java`
- `src/main/java/com/flashsale/inventory/infra/persistence/TransactionalStockDecrement.java`
- `src/main/java/com/flashsale/inventory/infra/redis/StockProjectionSyncLuaExecutor.java`
- `src/main/resources/lua/stock-projection-sync.lua`
- `src/test/java/com/flashsale/inventory/infra/persistence/PostgresStockDecrementAdapterTest.java`
- `src/test/java/com/flashsale/inventory/infra/redis/StockProjectionSyncLuaExecutorTest.java`
- `src/test/java/com/flashsale/inventory/integration/InventoryInfrastructureTestSupport.java`
- `src/test/java/com/flashsale/inventory/integration/DurableStockDecrementIntegrationTest.java`
- `src/test/java/com/flashsale/inventory/integration/RedisPostgresFailoverIntegrationTest.java`
- `src/test/java/com/flashsale/inventory/integration/StockProjectionSyncIntegrationTest.java`

## 14. Files To Modify

- `build.gradle`: add test-scoped Spring Boot Testcontainers, Testcontainers JUnit, and PostgreSQL dependencies.
- `application/StockCounterService.java`: implement canonical orchestration.
- `application/StockDecrementResult.java`: remove `CacheMiss`.
- `application/port/StockFallbackPort.java` → `DurableStockDecrementPort.java`.
- `application/port/StockRewarmPort.java` → `StockProjectionSyncPort.java`.
- `application/port/StockRewarmUnavailableException.java` → `StockProjectionSyncUnavailableException.java`.
- `infra/persistence/PostgresStockFallbackAdapter.java` → `PostgresStockDecrementAdapter.java`.
- `infra/persistence/ProductPersistenceMapper.java`: expose/identify the managed updated StockLevel.
- `infra/persistence/ProductRepository.java`: translate Product validation read infrastructure failures without changing missing-Product semantics.
- `infra/config/RedisScriptConfiguration.java`: singleton typed synchronization script bean.
- `infra/redis/RedisStockDecrementAdapter.java`: translate connection loss, command timeout, and other indeterminate transport failures while preserving fail-closed deterministic failures.
- `infra/redis/RedisStockRewarmAdapter.java` → `RedisStockProjectionSyncAdapter.java`.
- `application/StockCounterServiceTest.java`.
- `infra/persistence/PostgresStockFallbackAdapterTest.java` → `TransactionalStockDecrementTest.java`.
- `infra/persistence/ProductPersistenceMapperTest.java`.
- `infra/persistence/ProductRepositoryTest.java`.
- `infra/config/RedisScriptConfigurationTest.java`.
- `infra/redis/RedisStockDecrementAdapterTest.java`.
- `infra/redis/RedisStockRewarmAdapterTest.java` → `RedisStockProjectionSyncAdapterTest.java`.

All Java paths are under the matching `src/main/java/com/flashsale/inventory` or `src/test/java/com/flashsale/inventory` root.

## 15. Files That MUST NOT Change

- `src/main/java/com/flashsale/inventory/domain/**`
- `ProductJpaEntity.java`
- `StockLevelJpaEntity.java`
- `SpringDataProductRepository.java`
- `application/port/ProductRepository.java`.
- `StockDecrementPort.java`
- `StockDecrementUnavailableException.java`
- `StockDecrementLuaExecutor.java`
- `src/main/resources/db/migration/V1__init.sql`
- `stock-decrement.lua`
- `stock-prewarm.lua`
- `stock-release.lua`
- `stock-reconcile.lua`
- `application.yml`
- Repository-root `build.gradle` and `settings.gradle`
- Entire `services/sale-service/**`
- Docker, Makefile, Kafka, REST, Reservation, and documentation files other than this contract.

## 16. Package Rules

- `domain`: aggregates, entities, value objects only.
- `application`: orchestration and application results.
- `application.port`: infrastructure-neutral contracts/exceptions.
- `infra.persistence`: JPA, mapping, transactions, PostgreSQL adapters.
- `infra.redis`: Lua execution and Redis adapters.
- `infra.config`: Spring bean construction only.
- `integration`: infrastructure/cross-adapter tests only.
- Add no API or event package.

## 17. Dependency Rules

- Domain: JDK and Inventory domain types only.
- Application's inward Inventory package dependencies: domain and ports only; the existing Spring service stereotype and logging facade are permitted.
- Application must not import infrastructure.
- Ports must not expose Spring, JPA, Redis, PostgreSQL, or transaction types.
- Infrastructure depends inward.
- Redis adapters/executors cannot decide business outcomes.
- Testcontainers dependencies remain `testImplementation`.
- No service-to-service Java dependency.

## 18. Error Handling Rules

- Missing Product/StockLevel: `NoSuchElementException`.
- Invalid quantity: `IllegalArgumentException`.
- Invalid Redis result: `IllegalStateException`.
- Product validation read infrastructure failure: `DurableStockUnavailableException`; do not invoke Redis.
- Redis connection loss, command timeout, or another indeterminate transport failure: continue to durable decrement.
- Deterministic Redis script, data, or serialization failure: fail closed; do not invoke durable decrement.
- Durable store/commit unavailable: `DurableStockUnavailableException`.
- Redis success plus durable insufficiency: `SoldOut` plus warning.
- Redis sold-out plus durable success: `Decremented` plus warning.
- Post-commit synchronization failure: warning plus durable result.
- No retries.
- No `stock-release.lua` compensation.

## 19. Testing Requirements

Unit tests:

- Every recognized Redis path and every indeterminate Redis transport-failure path invokes durable port once.
- PostgreSQL result determines output.
- Invalid Redis output cannot produce success.
- Synchronization occurs only after durable completion.
- Synchronization failures preserve durable results.
- Mismatches warn.
- No retry/compensation.
- Adapter delegation, result mapping, exception translation, key construction, script bean identity, mapper behavior.
- Product validation read infrastructure failure is translated to `DurableStockUnavailableException` and does not invoke Redis.
- Redis connection loss and command timeout are translated as indeterminate; deterministic script, data, and serialization failures remain fail-closed.
- Equal projection revision reapplies authoritative stock; strictly older revision is ignored.
- Existing stock without a revision is atomically invalidated and returns `MISSING`.

Real PostgreSQL tests:

- Flyway and Hibernate validation.
- Pessimistic locking and concurrent decrements.
- No negative stock or excess success.
- Success/insufficiency version behavior.
- Commit/transaction failure behavior.

Real Redis/cross-store tests:

- Lua decrement.
- Success, miss, unavailable Redis.
- Failure before and after Lua execution.
- Both disagreement directions.
- Revision fencing, equal-revision repair, strictly older revision, and out-of-order sync.
- TTL preservation for persistent and expiring stock keys, revision-expiry mirroring, and missing-key behavior.
- Independent revision-key loss invalidates the remaining stock key and cannot admit an older synchronization.
- Concurrent cache misses preserve serialized PostgreSQL correctness while Redis remains missing.

Verification:

- Run `./gradlew :services:inventory-service:cleanTest :services:inventory-service:build`.
- Run unchanged SaleService tests.
- Preserve all 71 existing Inventory tests.
- Report pass/fail/error/skip totals.
- Run `git diff --check`.
- Scan domain for framework imports.
- Scan application for infrastructure imports.
- Verify forbidden files unchanged.
- Verify JAR contains new contracts, adapters, executor, and Lua resource.

## 20. Acceptance Criteria

- Every `Decremented` maps to exactly one committed PostgreSQL decrement.
- PostgreSQL failure never returns success.
- Product validation read infrastructure failure becomes `DurableStockUnavailableException` and does not invoke Redis.
- All recognized Redis outcomes and indeterminate transport failures resolve through PostgreSQL.
- Durable insufficiency always returns `SoldOut`.
- Concurrency never produces negative stock or excess successes.
- Ambiguous Redis execution causes one durable invocation.
- Strictly older projection revisions cannot overwrite current or newer state.
- Equal projection revisions idempotently reapply their authoritative durable state.
- Existing stock without a revision is invalidated and cannot establish a request-time baseline.
- Concurrent cache misses remain absent from Redis while PostgreSQL results remain correct.
- Applied synchronization never changes a trusted stock key's expiry, mirrors that expiry on the revision key, and never recreates missing stock keys.
- Synchronization failure never conceals committed outcome.
- Existing 71 Inventory tests, new tests, and SaleService regression tests pass.
- Dependency scans remain clean.
- No forbidden file or excluded feature changes.

## 21. Implementation Order

1. Add result types, exceptions, and renamed port contracts.
2. Implement transactional durable component and PostgreSQL adapter.
3. Update persistence mapper and persistence tests.
4. Add synchronization Lua, configuration, executor, and Redis adapter.
5. Update `StockCounterService`, Product validation failure translation, and Redis primary failure translation.
6. Update unit tests.
7. Add Testcontainers dependencies and integration support.
8. Add PostgreSQL, Redis, and cross-store integration tests.
9. Run complete verification and forbidden-change checks.

## 22. Critical Risks

- PostgreSQL transaction per potentially successful request increases latency/contention.
- Product-root locking serializes sales for one Product.
- Redis may remain conservatively low after durable failure.
- Missing Redis keys remain missing until pre-warm.
- Revisionless stock keys are invalidated; independent revision-key eviction can therefore increase PostgreSQL load until pre-warm.
- Future pre-warm/reconciliation must support revision keys.
- Standalone Redis tests do not prove cluster topology.
- Post-commit response loss plus client retry can double-decrement.
- SaleService migration defect remains unresolved.

## 23. Implementation Assumptions and Non-Assumptions

- PostgreSQL `stock_levels.current_stock` and its persisted StockLevel version remain the only authoritative stock and projection revision.
- Redis stock and revision keys may disappear independently; the implementation must not assume co-eviction.
- An indeterminate Redis transport failure may occur before or after Lua execution; the implementation must not assume that a timeout means the script did not execute.
- Missing or invalidated Redis stock remains missing until a future pre-warm capability, which remains out of scope.
- The existing Product lookup remains the canonical pre-validation step.

## 24. Non-negotiable Constraints

- Implement only this contract.
- Do not redesign, extend scope, or optimize.
- PostgreSQL remains authoritative for every acknowledged decrement.
- Preserve all transaction and sequencing rules.
- Preserve all domain and dependency boundaries.
- Do not introduce distributed transactions, compensation, retries, APIs, events, migrations, or documentation changes.
- Stop if compliance requires modifying a prohibited file or changing an architectural decision.

## 25. Revision Record

Revision number: 2

Summary of accepted changes:

- Revisionless stock is invalidated instead of accepting an unsafe request-time revision baseline.
- Equal projection revisions idempotently reapply authoritative durable stock; only strictly older revisions are ignored.
- Concurrent cache-miss wording and tests distinguish PostgreSQL correctness from a Redis key that remains missing.
- Redis connection loss, command timeout, and other indeterminate transport failures are translated to the existing durable path; deterministic failures remain fail-closed.
- Product validation read infrastructure failures are translated at the persistence boundary without changing missing-Product behavior.
- Application dependency wording permits the existing Spring service stereotype and logging facade while continuing to prohibit infrastructure imports.
- Stock-key expiry remains unchanged and revision-key expiry mirrors it for both persistent and expiring stock.

Summary of rejected changes:

- No new count-specific disagreement-warning requirement was added because the existing contract already requires warnings for every Redis/PostgreSQL mismatch.

This document supersedes Revision 1.

Architecture Frozen for Implementation
