# Architecture Decisions
## Flash Sale Platform
**Source:** Final-Spec-Council.md v2.0 | **Date:** 2026-06-15
**Owner:** Staff Engineer Council — Jordan Wei (Google), Priya Nair (Uber), Marcus Shaw (Amazon), Elena Kovac (Atlassian)

---

# Decision 001
**Title:** Service Boundaries
**Status:** Approved — Supersedes v1 (3-service model)

**Chosen:**
- SaleService
- InventoryService
- OrderService
- NotificationService
- AnalyticsService

**Reason:**
Council consensus. Each service passes the split criteria: distinct bounded context, divergent scaling profile, independent failure mode. SaleService owns the sale state machine — a read-heavy domain that must not pollute InventoryService. AnalyticsService is a pure Kafka consumer with zero coupling to the transactional path.

**Alternatives Considered:**
- 3 services (InventoryService, OrderService, NotificationService) — v1 model
- 4 services (merge SaleService into InventoryService)

**Rejected Because:**
- 3-service model embeds flash sale lifecycle into InventoryService, violating Single Responsibility and creating a read/write hotspot at sale start.
- 4-service model pushes analytical workloads onto transactional Postgres, which cannot sustain columnar query volumes.

**Impact:**
5 independent deployables. Each owns its Dockerfile, Helm chart, HPA config, and database schema. No cross-service DB joins permitted under any circumstances.

**Dissent on record:** Elena Kovac (Atlassian) — teams below 20 engineers may prefer the 3-service model to reduce cognitive overhead of 5 deploy targets.

---

# Decision 002
**Title:** Database Ownership — Database-per-Service
**Status:** Approved — Carried forward from ADR-008

**Chosen:**
- `sales_db` → SaleService (tables: `flash_sales`, `sale_schedules`)
- `inventory_db` → InventoryService (tables: `products`, `stock_levels`, `reservations`)
- `orders_db` → OrderService (tables: `orders`, `order_outbox`, `idempotency_keys`)
- ClickHouse → AnalyticsService (table: `sale_events` wide columnar)
- NotificationService → no persistent storage (stateless)

**Reason:**
Shared databases create hidden coupling and schema contention across service boundaries. Separate schemas enforce ownership, allow independent schema evolution, and make incident scoping unambiguous — a slow `orders_db` pages the Order team only.

**Alternatives Considered:**
- Shared PostgreSQL instance with schema-per-service
- Single monolithic database

**Rejected Because:**
- Schema-per-service on one instance still couples services at the infrastructure level; one runaway query can starve all services.
- Monolithic database is a single point of failure and creates a deployment bottleneck.

**Impact:**
Zero cross-service joins enforced at the team level. Cross-service data access goes through Kafka events (eventual consistency) or a service's read API (synchronous, user-facing reads only). Direct DB access by another service is an architectural violation.

---

# Decision 003
**Title:** Stock Decrement Strategy — Redis Lua Atomic Script
**Status:** Approved — Carried forward from ADR-001

**Chosen:**
Atomic Lua script executed in Redis:
```lua
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -2 end   -- miss → fallback
if stock <= 0   then return -1 end   -- sold out → 409
redis.call('DECR', KEYS[1])
return stock - 1
```
Return codes: `-2` = cache miss, `-1` = sold out, `>= 0` = success.

**Reason:**
Redis DECR alone is atomic but cannot enforce a floor of zero. A Lua script executes as a single atomic Redis command — no race condition possible between the check and the decrement. This is the only mechanism that prevents overselling under concurrent load.

**Alternatives Considered:**
- `SELECT FOR UPDATE` in Postgres on every reservation request
- Optimistic locking with version column in Postgres
- Redis DECR without floor check

**Rejected Because:**
- Postgres `SELECT FOR UPDATE` on hot path: row lock contention at 50k RPS causes lock queue saturation. Acceptable only as a fallback.
- Optimistic locking: high retry rate under contention, unpredictable latency at P99.
- Bare DECR: stock can go negative; overselling guaranteed under race conditions.

**Impact:**
Reservation latency P99 target: < 50ms. Oversell rate target: 0. Postgres `SELECT FOR UPDATE` remains the mandatory fallback when Redis is unavailable.

---

# Decision 004
**Title:** Redis Fallback — SELECT FOR UPDATE
**Status:** Approved

**Chosen:**
When Redis is unavailable or returns a cache miss (return code `-2`), InventoryService falls back to:
```sql
SELECT stock FROM products WHERE id = ? FOR UPDATE
```
Stock is decremented in Postgres transactionally. Cache is re-warmed on success.

**Reason:**
A Redis miss must never return a guess. The only safe fallback is a serialized write to the authoritative data store. Postgres with row-level locking guarantees correctness at the cost of throughput.

**Alternatives Considered:**
- Return 503 on Redis miss (reject all reservations if Redis is down)
- Allow reservation without stock check on miss (optimistic, reconcile later)

**Rejected Because:**
- Hard 503: unacceptable availability degradation; Redis being slow should not stop a sale entirely.
- Optimistic skip: overselling risk is unacceptable for this domain.

**Impact:**
Throughput degrades significantly on Redis failure (Postgres lock contention). This is an intentional tradeoff — correctness over availability for inventory. SLA: Redis must have 99.9% uptime to keep the fallback path cold.

---

# Decision 005
**Title:** Concurrency Model — Java 21 Virtual Threads
**Status:** Approved — Carried forward from ADR-002

**Chosen:**
Java 21 Virtual Threads (Project Loom) via Spring Boot's `spring.threads.virtual.enabled=true`.

**Reason:**
Virtual threads eliminate the thread-per-request ceiling without requiring reactive programming (WebFlux). Under I/O-heavy workloads (Redis, Postgres, Kafka), virtual threads park during blocking calls and resume with negligible scheduler overhead. This delivers near-reactive throughput with imperative code that is easier to reason about, debug, and test.

**Alternatives Considered:**
- Spring WebFlux with Project Reactor (reactive)
- Traditional platform threads with thread pool tuning

**Rejected Because:**
- WebFlux: reactive programming model increases cognitive load significantly. Stack traces are fragmented. Testing is harder. No throughput advantage over virtual threads for this workload profile.
- Platform threads: thread pool becomes a bottleneck under sustained connection concurrency; tuning is fragile and environment-specific.

**Impact:**
Simpler code. Blocking JDBC and Redis calls are used without wrappers. Observability (stack traces, thread dumps) remains readable. No changes to Spring MVC programming model.

---

# Decision 006
**Title:** Inter-Service Communication — Kafka for Async, HTTP for Sync
**Status:** Approved — Carried forward from ADR-003

**Chosen:**
- All state-change notifications between services: Kafka events (async, at-least-once)
- Synchronous user-facing reads across service boundaries: HTTP REST (e.g., OrderService calling SaleService `/active` endpoint)
- Hot reservation path: Kafka only — no synchronous HTTP between InventoryService and OrderService

**Reason:**
Synchronous HTTP between services on the hot path creates temporal coupling. If OrderService is slow, InventoryService blocks. Kafka decouples services so each can fail and recover independently. The cardinal rule: Kafka is async fan-out only, never used as synchronous RPC.

**Alternatives Considered:**
- gRPC for all inter-service communication
- REST for all inter-service communication
- Kafka for everything including query responses (request-reply pattern)

**Rejected Because:**
- gRPC everywhere: adds proto schema management overhead; no meaningful latency benefit at this scale.
- REST everywhere: tight coupling; cascade failures; reservation flow becomes synchronous chain.
- Kafka request-reply: anti-pattern that adds complexity with no benefit over HTTP for synchronous reads.

**Impact:**
Services are independently deployable and fault-tolerant. A NotificationService outage has zero impact on reservation or order creation. Each service scales its Kafka consumer group independently.

---

# Decision 007
**Title:** Kafka Topic Design and Partition Strategy
**Status:** Approved — ADR-013 overrides ADR-006 for inventory-events partition key

**Chosen:**

| Topic | Partitions | Partition Key | Retention |
|---|---|---|---|
| `sale-events` | 8 | `saleId` | 7 days |
| `inventory-events` | 16 | `productId` | 3 days |
| `order-events` | 8 | `saleId` | 3 days |
| `notifications.dlq` | 4 | none | 14 days |

**Reason:**
`inventory-events` must use `productId` as the partition key — not `saleId`. Two concurrent reservations for the same product must be processed sequentially by the OrderService consumer to guarantee correct stock confirmation. `saleId` would scatter same-product events across partitions, breaking ordering. `sale-events` and `order-events` use `saleId` to preserve sale-level ordering for saga correctness.

**Alternatives Considered:**
- All topics partitioned by `saleId` (v1 / council session initial proposal)
- `inventory-events` partitioned by `userId`
- Random partitioning for throughput

**Rejected Because:**
- `saleId` for `inventory-events`: breaks per-product ordering; concurrent reservation confirmations can arrive out of order, causing incorrect stock state.
- `userId`: no ordering guarantee needed per user; scatters related product events.
- Random: maximum throughput but zero ordering guarantee; saga correctness breaks.

**Impact:**
`inventory-events` has 16 partitions (2× others) to handle higher throughput from concurrent reservations. Consumer group `order-svc-reservation-consumer` processes per-product events in strict order.

---

# Decision 008
**Title:** Event Reliability — Transactional Outbox Pattern
**Status:** Approved — Carried forward from ADR-004

**Chosen:**
Write the Kafka event to an `order_outbox` table in the same DB transaction as the business record. A background poller publishes unpublished outbox rows to Kafka and marks them `published = true`.

**Reason:**
Publishing a Kafka event and committing a DB transaction are two distinct operations. Either can fail independently. Without the outbox, a process crash between commit and publish creates a permanently lost event. The outbox makes event publishing part of the DB transaction — atomicity is guaranteed by the database, not the application.

**Alternatives Considered:**
- Direct Kafka publish within the same application transaction (dual-write)
- Change Data Capture via Debezium
- Publish-then-commit ordering

**Rejected Because:**
- Dual-write: no atomicity guarantee between DB commit and Kafka publish; event loss on crash.
- Debezium: operationally complex for v1; requires Kafka Connect infrastructure and CDC configuration.
- Publish-then-commit: event published for an order that then fails to persist; downstream services act on phantom orders.

**Impact:**
At-least-once event delivery guaranteed. Outbox poller is a background scheduled task within each producing service. Idempotent consumers handle duplicates.

---

# Decision 009
**Title:** Order Idempotency — Dual-Layer Key Check
**Status:** Approved

**Chosen:**
Every mutating endpoint accepts `Idempotency-Key: <UUID v4>` header. Two-layer check:
1. Redis (`idem:{key}`, TTL 24h) — fast path, in-memory
2. Postgres `idempotency_keys` table — durable fallback

Processing flow: check Redis → check Postgres → process → write result to both → return.

**Reason:**
Network retries are not optional — they are guaranteed. A client that times out will retry. Without idempotency, retries create duplicate orders. The dual-layer approach ensures correctness even if Redis is cold or evicted the key before the 24h TTL.

**Alternatives Considered:**
- Redis-only idempotency key storage
- Postgres-only idempotency key storage
- Application-level deduplication by (userId, saleId) tuple

**Rejected Because:**
- Redis-only: key can be evicted under memory pressure before TTL expires; duplicate processing possible.
- Postgres-only: every request hits the DB for the idempotency check; eliminates the Redis fast path.
- (userId, saleId) dedup: not a general solution; breaks for legitimate multi-order scenarios in future.

**Impact:**
100% idempotency target. All OrderService mutation endpoints require the header. Clients without a key receive `400 Bad Request`.

---

# Decision 010
**Title:** Saga Pattern — Choreography over Orchestration
**Status:** Approved — Carried forward from ADR-012

**Chosen:**
Choreography-based saga via Kafka events. No central orchestrator service.

Event flow:
```
InventoryService  →[StockReserved]→   OrderService
OrderService      →[OrderCreated]→    NotificationService, AnalyticsService
PaymentService    →[PaymentFailed]→   InventoryService (releases reservation)
```
Compensating transactions published as events on failure paths.

**Reason:**
At 5 services, a central saga orchestrator adds a deployment unit, a SPOF, and a coordination bottleneck without meaningful benefit. Choreography keeps services decoupled. Each service reacts to events it cares about. The tradeoff — harder to visualize the overall flow — is mitigated by explicit documentation and distributed tracing.

**Alternatives Considered:**
- Orchestration via a dedicated SagaOrchestrator service
- Two-phase commit (2PC) across services

**Rejected Because:**
- Orchestration: central orchestrator is a SPOF; adds a 6th service with no domain logic; network latency through orchestrator on every step.
- 2PC: does not work reliably across independent services and databases; blocking protocol; catastrophic failure modes on coordinator crash.

**Impact:**
Saga flow must be documented explicitly (this ADR log + sequence diagrams). Distributed tracing (Micrometer + Tempo) is mandatory — it is the substitute for the visibility an orchestrator would provide.

---

# Decision 011
**Title:** Redis Architecture — Three-Layer Contract
**Status:** Approved

**Chosen:**
Three purpose-built Redis layers with distinct contracts:

| Layer | Owner | Key Pattern | Structure | TTL | Fallback |
|---|---|---|---|---|---|
| Stock counter | InventoryService | `stock:{saleId}` | String | sale_end + 10min | SELECT FOR UPDATE |
| Rate limiter | API Gateway | `rate:{userId}:{window}` | Sorted Set | 60s | Fail-open + audit log |
| Session/Idempotency | OrderService | `session:{userId}`, `idem:{key}` | Hash / String | 5min / 24h | Postgres lookup |

**Reason:**
Each layer has a distinct data structure, access pattern, and failure mode. Conflating them into a generic cache increases blast radius — a rate limiter bug should not affect stock counters. Explicit layer ownership makes on-call investigation faster.

**Alternatives Considered:**
- Single Redis namespace for all use cases
- Separate Redis instances per layer

**Rejected Because:**
- Single namespace: key collision risk; no ownership boundary; harder to tune eviction per use case.
- Separate instances: operational overhead triples for marginal isolation benefit at this scale; Redis Cluster with keyspace conventions achieves logical isolation.

**Impact:**
Redis Cluster: 3 primary shards, 1 replica each. Eviction: `allkeys-lru`, 4 GB cap. Persistence: AOF `everysec`. Stampede guard on stock counter via probabilistic early refresh. Cardinal rule: Redis is never the source of truth.

---

# Decision 012
**Title:** Analytics Storage — ClickHouse over PostgreSQL
**Status:** Approved — ADR-011

**Chosen:**
ClickHouse as the storage engine for AnalyticsService. All Kafka events materialized into a `sale_events` wide columnar table. Dashboards query ClickHouse directly.

**Reason:**
Flash sale analytics are columnar read workloads: aggregate queries across millions of events (`SELECT COUNT(*) GROUP BY saleId`, time-series throughput graphs, per-product reservation rates). ClickHouse handles 10M+ rows/sec inserts and sub-second analytical queries. Running this workload on the same Postgres instance as OLTP would cause I/O contention and degrade transactional latency.

**Alternatives Considered:**
- PostgreSQL with partitioned tables for analytics
- Elasticsearch
- BigQuery / Redshift (cloud-managed)

**Rejected Because:**
- Postgres: columnar aggregations on millions of rows are slow; I/O contention with OLTP is a production incident waiting to happen.
- Elasticsearch: optimized for full-text search, not aggregate analytics; overkill for this use case.
- Cloud-managed: introduces external dependency and cost overhead not justified for a self-hosted production demo.

**Impact:**
AnalyticsService is a pure Kafka consumer. It writes to ClickHouse in micro-batches every 1 second. Analytics lag target: < 5 seconds. Zero coupling to the transactional path.

---

# Decision 013
**Title:** SaleService — Separate Bounded Context
**Status:** Approved — ADR-010

**Chosen:**
SaleService is a standalone service owning the flash sale state machine, scheduling, and lifecycle API.

State machine: `SCHEDULED → ACTIVE → ENDED → ARCHIVED`

Hot path read: `GET /api/v1/sales/{id}/active` served exclusively from Redis. Must never touch Postgres during an active sale.

**Reason:**
The sale status is read by every other service before processing any request. Embedding this in InventoryService forces inventory logic to resolve a cross-domain concern on every stock check. The `SCHEDULED → ACTIVE` transition is a time-triggered event that must be reliable and observable independently of stock operations. Separating it makes the state machine testable, the scaling profile tunable (read-heavy, aggressive cache), and the failure blast radius smaller.

**Alternatives Considered:**
- Embed sale lifecycle in InventoryService (v1 model)
- Embed sale lifecycle in OrderService

**Rejected Because:**
- InventoryService: violates SRP; sale status reads pollute the stock hot path; a sale scheduling bug can take down inventory operations.
- OrderService: orders are downstream of sales; an order service should not own the entity it depends on.

**Impact:**
SaleService publishes `SaleStarted` and `SaleEnded` events to `sale-events` topic. All other services react to these events to gate their own operations. SaleService Redis cache must be pre-warmed before `sale_start` time.

---

# Decision 014
**Title:** NotificationService — Stateless Async Consumer
**Status:** Approved

**Chosen:**
NotificationService is stateless. No database. Consumes all three Kafka topics. Dispatches to external providers (email, push, SMS). Never on the synchronous request path.

Dead-letter queue: `notifications.dlq` with 14-day retention. Ops alert on DLQ depth > 100 messages.

**Reason:**
Notification delivery is inherently eventually-consistent. A failed email does not roll back an order. Keeping the service stateless eliminates an entire category of operational concern (schema migrations, backup, replication lag). If delivery status tracking is needed in future, it becomes a new bounded context with its own schema.

**Alternatives Considered:**
- Stateful notification service with delivery tracking database
- Inline notification dispatch within OrderService

**Rejected Because:**
- Stateful: adds operational overhead (DB migrations, backup) for a concern that is explicitly eventually-consistent.
- Inline dispatch: couples notification latency to order creation latency; a slow email provider degrades order throughput.

**Impact:**
NotificationService outage has zero impact on reservation, inventory, or order creation. SLA for notifications is best-effort with at-least-once delivery via Kafka consumer retries.

---

# Decision 015
**Title:** Contradiction Resolution — v1 3-Service Model vs Council 5-Service Model
**Status:** Approved — Formal retirement of ADR-005

**Chosen:**
5-service model is the definitive final architecture. ADR-005 ("3 services is the right count") is formally retired and replaced by this decision.

**Reason:**
ADR-005 was correct within its stated scope: avoid microservice sprawl for a focused v1. The council session expanded the problem statement by introducing independent scaling requirements and analytics isolation. Both new services (SaleService, AnalyticsService) independently meet the split criteria established by ADR-005 itself: distinct domain, divergent scaling profile, independent failure mode. The principle of ADR-005 is preserved; its conclusion is updated.

The v1-to-council contradiction was an architectural governance failure — the council session added services without formally retiring ADR-005. This decision closes that gap.

**What v1 got right — carried forward unchanged:**
- Lua atomic stock decrement
- Java 21 virtual threads over WebFlux
- Kafka for all inter-service async communication
- Transactional Outbox pattern
- Database-per-service
- Idempotency-Key header on all mutating endpoints
- Choreography-based saga
- SELECT FOR UPDATE as the Redis fallback

**Alternatives Considered:**
- Revert to 3-service model (reject council additions)
- Accept 5-service model without formal ADR retirement

**Rejected Because:**
- Revert: SaleService and AnalyticsService both pass the split criteria; reverting sacrifices valid domain separation.
- No formal retirement: leaves ADR-005 active in conflict with the new model; creates ambiguity for future contributors.

**Impact:**
All future service addition proposals must be evaluated against the split criteria: distinct bounded context, divergent scaling profile, independent failure mode. A 6th service that does not meet all three criteria is rejected.

---

*All 15 decisions extracted from Final-Spec-Council.md v2.0.*
*Next: generate service skeletons → integration tests for saga flow → Lua script with property-based tests.*