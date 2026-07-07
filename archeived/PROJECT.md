# PROJECT.md — Flash Sale Platform

**Purpose of this document:** This is the canonical, generated reference for the Flash Sale Platform repository. It contains only facts extracted directly from the repository's own documentation — nothing has been invented, assumed, or filled in. It exists so that any future AI-assisted work on this repository starts from the same verified ground truth, per the rules in `AI-CONTEXT.md`.

**Generation date:** 2026-07-02
**Generated from:** 11 files in `/mnt/user-data/uploads` (full list in §16)
**Status:** No implementation exists yet. This document describes specification and design only.

---

## 0. Ground Rules (from AI-CONTEXT.md)

These rules govern this document and all future work on this repository:

- Never invent packages, classes, services, folders, or infrastructure.
- If documentation conflicts, stop and ask — do not silently resolve.
- The repository is always the source of truth.

**Declared source-of-truth precedence (highest first), verbatim from `AI-CONTEXT.md`:**

| Rank | Source |
|---|---|
| 1 | `README.md` |
| 2 | `PRD-FlashSalePlatform.md` |
| 3 | `Final-Spec-Council.md` |
| 4 | ADRs (`01-Decisions.md`) |
| 5 | `DomainModel.md` |
| 6 | `DatabaseSchema.md` |
| 7 | `schema.sql` |

`Build-Plan.md`, `RedisDesign.md`, and `KafkaDesign.md` exist in the repository but are **not assigned a rank** by `AI-CONTEXT.md`. They are treated below as supplementary detailed-design documents, subordinate to the ranked list above. Where they conflict with a ranked document, or with each other, this is flagged explicitly in §15 rather than resolved.

---

## 1. Project Overview

**Name:** Flash Sale Platform
**Stated purpose (AI-CONTEXT.md):** "Build a production-grade flash sale platform for learning distributed systems."

**Problem statement (PRD §1.1):** Time-limited flash sales collapse demand into a narrow window, creating a thundering-herd problem. This causes overselling (inventory correctness failure under concurrency), degraded user experience (timeouts, duplicate orders), and loss of operational visibility exactly when it's most needed.

**Product goal (PRD §1.2):** Build a backend platform that runs flash sales at scale — correct inventory, fair allocation, idempotent ordering, and observable operations — without sacrificing latency under peak load.

**Document status:**
- PRD-FlashSalePlatform.md — v1.0, "Approved for Engineering," dated 2026-06-15
- Final-Spec-Council.md — v2.0, "FINAL," dated 2026-06-15, supersedes `Architecture-v1.md` (2026-06-14)
- DomainModel.md — v1.0, "Final," dated 2026-06-15
- DatabaseSchema.md — v1.0, "Final," dated 2026-06-15
- RedisDesign.md / KafkaDesign.md — v1.0, "Final," dated 2026-06-15
- Build-Plan.md — v1.0, "Approved for execution," dated 2026-06-15
- 01-Decisions.md — Decisions 001–015 extracted from Final-Spec-Council v2.0; Decisions 016–019 added 2026-06-17

**Reviewers / Council (named consistently across PRD, Final-Spec-Council, DomainModel, 01-Decisions):** Jordan Wei (Google), Priya Nair (Uber), Marcus Shaw (Amazon), Elena Kovac (Atlassian), plus a Staff Engineer mentor.

---

## 2. Scope (PRD §1.3)

**In scope for v1.0:**
- Flash sale creation, scheduling, and lifecycle management
- Concurrent inventory reservation with atomic stock control
- Idempotent order creation with saga-based consistency
- Async notifications (email, push, SMS) on key events
- Real-time analytics event ingestion

**Out of scope for v1.0:**
- Payment processing (stubbed — PaymentService is a future bounded context)
- User authentication and identity (assumed provided by an upstream API gateway)
- Product catalog management (products are pre-loaded; no catalog API)
- Customer-facing storefront (this is a backend platform; UI is out of scope)

---

## 3. Personas / Actors (PRD §2)

| Actor | Type | Goals |
|---|---|---|
| Buyer | End user | Reserve a product before sellout; get confirmation; not be double-charged on retry |
| Sale Administrator | Internal operator | Create/schedule sales, monitor stock, force-end a sale |
| Platform Operator (SRE / On-Call) | Internal operator | Real-time visibility, alerting, fast incident diagnosis |
| Scheduler | System actor | Internal time-based trigger transitioning sale status (`SCHEDULED→ACTIVE` at `sale_start`; `ACTIVE→ENDED` at `sale_end` or stock=0) |

10 user stories are defined in PRD §3: US-001–004 (Sale Administrator), US-005–008 (Buyer), US-009–010 (Platform Operator).

---

## 4. Service Inventory

The platform consists of **5 independently deployable services** (Final-Spec-Council.md §1, confirmed by ADR Decision 001 / ADR-009).

| Service | Owns | DB / Schema | Kafka Role | Redis Role |
|---|---|---|---|---|
| **SaleService** | Sale lifecycle, scheduling, status machine | `sales_db` | Producer: `sale-events` | Cache: active sale metadata (TTL = sale duration) |
| **InventoryService** | Stock levels, reservation, atomic decrement | `inventory_db` | Producer: `inventory-events` | Layer 1: stock counter (Lua DECR) |
| **OrderService** | Order lifecycle, idempotency, saga orchestration | `orders_db` | Producer: `order-events`; Consumer: `inventory-events` | Layer 3: idempotency key cache (TTL 24h) |
| **NotificationService** | Email, push, SMS fan-out | None (stateless) | Consumer: all three topics | None |
| **AnalyticsService** | Event ingestion, metrics, dashboards | ClickHouse | Consumer: all three topics | None |

**Hard rules enforced across all 5 services (Final-Spec-Council.md §1):**
- Zero cross-service database joins. Cross-service data needs go through events or dedicated query APIs.
- No synchronous HTTP calls between services on the hot path (reserve → order flow). Kafka only.
- Each service is independently deployable with its own Dockerfile, Helm chart, and HPA config.

### 4.1 Why 5 services, not 3 (contradiction history — Final-Spec-Council.md §0, ADR Decision 015)

An earlier design (`Architecture-v1.md`, 2026-06-14) proposed 3 services: InventoryService, OrderService, NotificationService. A council session on 2026-06-15 added SaleService and AnalyticsService without formally retiring the prior "3 services is correct" decision (ADR-005) — logged as "an architectural governance failure." This was formally resolved by ADR Decision 015: the 5-service model is final; ADR-005 is formally retired; its underlying principle ("no sprawl without traffic justification") is preserved and satisfied by both new services (distinct bounded context, divergent scaling profile, independent failure mode).

**Dissent on record (Final-Spec-Council.md §0, 01-Decisions.md Decision 001):** Elena Kovac (Atlassian) — teams below 20 engineers may prefer merging SaleService back into InventoryService to reduce the cognitive overhead of 5 deploy targets. This spec targets a team of 4–8 engineers.

### 4.2 Split criteria for future services (ADR Decision 015)

Any proposed 6th service must satisfy all three: distinct bounded context, divergent scaling profile, independent failure mode.

### 4.3 Kubernetes resource baselines (PRD NFR-009)

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit | Min Replicas | Max Replicas |
|---|---|---|---|---|---|---|
| SaleService | 250m | 1000m | 512Mi | 1Gi | 2 | 8 |
| InventoryService | 250m | 1000m | 512Mi | 1Gi | 2 | 10 |
| OrderService | 250m | 1000m | 512Mi | 1Gi | 2 | 10 |
| NotificationService | 100m | 500m | 256Mi | 512Mi | 1 | 5 |
| AnalyticsService | 100m | 500m | 256Mi | 512Mi | 1 | 5 |

HPA trigger: CPU utilisation > 70% (PRD NFR-009).

**Note:** No document assigns individual HTTP application-port numbers to the 5 services (e.g. which port SaleService listens on). This is a documentation gap, not a stated design choice.

---

## 5. Technology Stack (verified mentions only)

| Layer | Technology | Verified detail | Source |
|---|---|---|---|
| Language / runtime | Java 21 | Virtual Threads (Project Loom), sealed interfaces, records, pattern matching in `switch` | 01-Decisions.md Decision 005, DomainModel.md §10 |
| Web framework | Spring Boot 3 | `spring.threads.virtual.enabled=true`; Spring Boot Actuator endpoints | Build-Plan.md, PRD FR-035 |
| Messaging | Apache Kafka 3.7.0 | KRaft mode (no Zookeeper); `AUTO_CREATE_TOPICS_ENABLE=false`; `enable.auto.commit=false`; producer `acks=all`, `enable-idempotence=true` | 01-Decisions.md Decision 017 |
| Cache / in-memory | Redis Cluster | 3 primary shards, 1 replica each (6 nodes); AOF `everysec`; `allkeys-lru` eviction | 01-Decisions.md Decision 016, README.md |
| Redis client tool | redis-cli 7.x | Minimum version for the CLI tool (server version not explicitly pinned anywhere) | README.md §1 |
| Relational DB | PostgreSQL 16 (16+) | 3 fully isolated instances (not schemas) | 01-Decisions.md Decision 019, DatabaseSchema.md |
| Analytics DB | ClickHouse 24.3 | `MergeTree` engine, partitioned by `toYYYYMM(occurred_at)`, 90-day TTL | 01-Decisions.md Decision 018 |
| Migrations | Flyway | Referenced as in-use (Build-Plan Week 1, `01-Decisions.md`'s "next decisions" note); a formal ADR for migration strategy (ADR-020) is **not yet written** | Build-Plan.md, 01-Decisions.md |
| Local orchestration | Docker Engine 24.0+, Docker Compose v2 (plugin, not v1 hyphenated) | 17-container stack (see §13 for a discrepancy) | README.md §1 |
| Production orchestration | Kubernetes + Helm | Deployment, Service, HPA, ConfigMap, Secret per service | Build-Plan.md Week 10 |
| Observability | Micrometer, Prometheus, OpenTelemetry, Tempo (or compatible backend) | `/actuator/prometheus`; 100% trace sampling on error, 10% on success | PRD NFR-024/025, 01-Decisions.md Decision 010 |
| Load testing | Gatling or k6 | 50,000 concurrent user simulation | PRD NFR-030, Build-Plan Week 10 |
| Property-based testing | jqwik (or similar) | Applied to the Redis Lua stock-decrement script | PRD NFR-031, Build-Plan Week 3 |
| Integration testing | Testcontainers | Real Postgres, Redis, Kafka instances in tests | PRD NFR-029 |
| Misc. code-sample annotations | `@RequiredArgsConstructor` (Lombok) | Appears in RedisDesign.md Java samples | RedisDesign.md §12 |

**Package root (DomainModel.md §10):** `com.flashsale.` with subpackages `sale/`, `inventory/`, `order/`, `notification/`, `analytics/`, each following `domain/{aggregate,entity,vo,event}`, `application/`, `infra/`. This structure is documented and must not be deviated from without updating DomainModel.md (per AI-CONTEXT.md: never invent packages).

---

## 6. Domain Model (DomainModel.md — DDD)

### 6.1 Aggregate roots (4 total)

Council split criteria for an aggregate root — **all three** must hold: invariant ownership, lifecycle independence, exactly one team owns its mutation.

| Aggregate | Owning service / schema | Core invariant | State machine |
|---|---|---|---|
| `FlashSale` | SaleService / `sales_db` | Status transitions are the only valid path; no state skippable; no reverse transition except `ACTIVE→ENDED` via admin | `SCHEDULED → ACTIVE → ENDED → ARCHIVED` (async) |
| `Product` | InventoryService / `inventory_db` | Total stock allocated across all active sales for a product must never exceed available stock | n/a (no explicit state machine) |
| `Reservation` | InventoryService / `inventory_db` | A reservation holds stock for exactly one user for a finite window; a user may not hold >1 active reservation per sale | `PENDING → CONFIRMED / EXPIRED / RELEASED` |
| `Order` | OrderService / `orders_db` | Exactly one `Order` may exist per `IdempotencyKey` | `PENDING → CONFIRMED / CANCELLED / EXPIRED` |

`Reservation` is a separate aggregate root (not a child of `Product`) specifically because it has an independent lifecycle (can expire while stock remains) and embedding it in `Product` would force product-level locking on every reservation, defeating the purpose of the Lua script (DomainModel.md §1).

### 6.2 Entities (child objects inside aggregates)

| Entity | Inside aggregate | Identity | Note |
|---|---|---|---|
| `SaleSchedule` | `FlashSale` | `scheduleId` | Immutable once sale is `ACTIVE`; holds `saleStart`, `saleEnd`, `timezone` |
| `StockLevel` | `Product` | `productId + saleId` | Postgres is durable record; Redis is the fast projection |
| `OutboxEvent` | `Order` | `outboxEventId` | Part of the `Order` aggregate, not a side effect — written in the same transaction |
| `IdempotencyRecord` | `Order` | `idempotencyKey` | Two-layer: Redis (fast, 24h TTL) + Postgres (durable) |

### 6.3 Value objects (all Java 21 `record`s with validating compact constructors)

| Value Object | Invariant |
|---|---|
| `SaleId`, `ProductId`, `OrderId`, `ReservationId`, `UserId` | Typed UUID wrappers — a raw `UUID` is never passed where a typed ID is expected |
| `SaleWindow` | `end` strictly after `start`; provides `isOpen()`, `isUpcoming()`, `hasPassed()`, `duration()` |
| `StockCount` | Value ≥ 0; provides `isAvailable()`, `isSoldOut()`, `decrement()`, `increment()`, `canDecrement()` |
| `Quantity` | Value ≥ 1 |
| `ReservationExpiry` | Expiry must be in the future at creation; `isExpired()`, `remainingTtl()` |
| `Money` | Amount ≥ 0; currency required; `add()` rejects mismatched currencies |
| `IdempotencyKey` | Valid UUID v4; TTL contract fixed at 24 hours |

### 6.4 Bounded contexts (one per service)

| Context | Service | Notable vocabulary rule |
|---|---|---|
| SaleContext | SaleService | Source of truth for `FlashSale`/`SaleStatus`/`SaleWindow` |
| InventoryContext | InventoryService | Does not know what a `FlashSale` is — `SaleId` is a reference only |
| OrderContext | OrderService | Deliberately does **not** use the word "Reservation" — uses `PurchaseIntent` instead, via an explicit Anti-Corruption Layer (ACL) |
| NotificationContext | NotificationService | **Conformist** — adopts upstream event model as-is, no business rules |
| AnalyticsContext | AnalyticsService | **Conformist** — consumes events exactly as published; adapts if upstream schema changes |

### 6.5 Context map / integration patterns (DomainModel.md §6)

| Upstream | Downstream | Pattern | Channel |
|---|---|---|---|
| SaleContext | InventoryContext | Partnership (co-evolve) | Kafka `sale-events` |
| InventoryContext | OrderContext | Customer/Supplier + ACL | Kafka `inventory-events` |
| SaleContext / InventoryContext / OrderContext | NotificationContext | Conformist | respective topics |
| SaleContext / InventoryContext / OrderContext | AnalyticsContext | Conformist | respective topics, projected to ClickHouse |

**The only ACL in the system** is InventoryContext → OrderContext: `Reservation`→`PurchaseIntent`, `ReservationId`→`PurchaseIntentId`, `StockReserved` event→`PurchaseConfirmation`, status terms `PENDING/CONFIRMED/EXPIRED`→`OPEN/CONSUMED/LAPSED` (DomainModel.md §8). Implemented in `InventoryEventTranslator` inside OrderService's `infra/` package.

### 6.6 Domain event envelope (DomainModel.md §9)

```
eventId        UUID v4 — deduplication key
eventType      e.g. "StockReserved"
eventVersion   e.g. "1.0" — backward-compatible minor bumps only; breaking change = new eventType
occurredAt     Instant
aggregateId    root entity ID
aggregateType  e.g. "Reservation"
payload        event-specific data
```
Consumers must ignore unknown fields (Jackson `FAIL_ON_UNKNOWN_PROPERTIES = false`).

### 6.7 Domain event catalogue (DomainModel.md §9)

| Event | Emitter | Topic | Partition Key | Trigger |
|---|---|---|---|---|
| `SaleScheduled` | SaleContext | `sale-events` | `saleId` | Sale created |
| `SaleStarted` | SaleContext | `sale-events` | `saleId` | `SCHEDULED→ACTIVE` |
| `SaleEnded` | SaleContext | `sale-events` | `saleId` | `ACTIVE→ENDED` |
| `StockAllocated` | InventoryContext | `inventory-events` | `productId` | Stock set for sale |
| `StockReserved` | InventoryContext | `inventory-events` | `productId` | Lua decrement success |
| `ReservationExpired` | InventoryContext | `inventory-events` | `productId` | TTL elapsed |
| `ReservationReleased` | InventoryContext | `inventory-events` | `productId` | Saga compensation |
| `OrderCreated` | OrderContext | `order-events` | `saleId` | Order placed |
| `OrderConfirmed` | OrderContext | `order-events` | `saleId` | Payment success (stub) |
| `OrderCancelled` | OrderContext | `order-events` | `saleId` | Saga compensation |

---

## 7. Architecture Decision Records (01-Decisions.md, 19 total)

| # | Title | Status |
|---|---|---|
| 001 | Service Boundaries (5 services) | Approved — supersedes v1 3-service model |
| 002 | Database Ownership — Database-per-Service | Approved — carried forward from ADR-008 |
| 003 | Stock Decrement Strategy — Redis Lua Atomic Script | Approved — carried forward from ADR-001 |
| 004 | Redis Fallback — `SELECT FOR UPDATE` | Approved |
| 005 | Concurrency Model — Java 21 Virtual Threads | Approved — carried forward from ADR-002 |
| 006 | Inter-Service Communication — Kafka async / HTTP sync | Approved — carried forward from ADR-003 |
| 007 | Kafka Topic Design and Partition Strategy | Approved — ADR-013 overrides ADR-006 for `inventory-events` key |
| 008 | Event Reliability — Transactional Outbox | Approved — carried forward from ADR-004 |
| 009 | Order Idempotency — Dual-Layer Key Check | Approved |
| 010 | Saga Pattern — Choreography over Orchestration | Approved — carried forward from ADR-012 |
| 011 | Redis Architecture — Three-Layer Contract | Approved |
| 012 | Analytics Storage — ClickHouse over PostgreSQL | Approved — ADR-011 |
| 013 | SaleService — Separate Bounded Context | Approved — ADR-010 |
| 014 | NotificationService — Stateless Async Consumer | Approved |
| 015 | Contradiction Resolution — v1 3-service vs council 5-service | Approved — formal retirement of ADR-005 |
| 016 | Redis Cluster — Topology, Persistence, Eviction Policy | Approved (2026-06-17) |
| 017 | Kafka — Event-Driven Architecture, KRaft Mode, Topic Ownership | Approved (2026-06-17) |
| 018 | ClickHouse — Analytics Storage Engine | Approved (2026-06-17) |
| 019 | PostgreSQL — Database-per-Service with Isolated Instances | Approved (2026-06-17) |

**What v1 got right and was carried forward unchanged (Final-Spec-Council.md §7, ADR Decision 015):**
Lua atomic stock decrement · Java 21 virtual threads over WebFlux · Kafka for all inter-service async communication · Transactional Outbox pattern · Database-per-service · `Idempotency-Key` header on all mutating endpoints · Choreography-based saga · `SELECT FOR UPDATE` as the Redis fallback (never a stock guess).

**Key one-line rationale per decision (for the ones not already covered elsewhere in this document):**
- **004 (Redis fallback):** A Redis miss must never return a guess; only a serialized write to the authoritative store is safe. Rejected alternatives: hard `503` on miss, or optimistic skip-the-check.
- **005 (Virtual Threads):** Chosen over WebFlux because it gives near-reactive throughput with imperative code — easier to reason about, debug, and test. Chosen over platform threads because thread-pool tuning is fragile under sustained concurrency.
- **006 (Kafka async / HTTP sync):** Synchronous HTTP on the hot path creates temporal coupling; Kafka decouples failure/recovery per service. Cardinal rule: Kafka is never synchronous RPC.
- **009 (Idempotency dual-layer):** Redis-only risks eviction before TTL; Postgres-only eliminates the fast path. Dual-layer keeps both correctness and speed.
- **010 (Choreography):** At 5 services, a central orchestrator is a SPOF and adds a 6th deployable with no domain logic. Trade-off (harder to visualize the flow) is mitigated by mandatory distributed tracing.
- **016 (Redis topology):** Redis Sentinel rejected (no horizontal sharding, single-primary bottleneck at 50k DECR/s). Standalone rejected (SPOF, no scale-out). Redis Enterprise rejected (cost/vendor lock-in). Multiple standalone instances rejected (3x operational overhead vs. keyspace-convention isolation in one cluster).
- **017 (Kafka KRaft):** Zookeeper rejected as a second distributed system to operate. RabbitMQ rejected (weaker per-partition ordering, no native log compaction). gRPC/HTTP-everywhere rejected (tight coupling). AWS SQS/SNS rejected (vendor lock-in, weak ordering, no offset replay).
- **018 (ClickHouse):** Postgres rejected for analytics (I/O contention with OLTP). Elasticsearch rejected (wrong workload shape). Druid rejected (too many operational node types for this scale). Cloud-managed (BigQuery/Redshift) rejected (vendor dependency, cost unpredictability).
- **019 (Postgres per-instance, not per-schema):** Schema-per-service on one instance rejected — a single runaway query or connection leak can still starve all three services. CockroachDB and PlanetScale rejected (operational complexity / MySQL dialect gaps respectively, notably `FOR UPDATE SKIP LOCKED` availability).

---

## 8. Database Design

### 8.1 Ownership (DatabaseSchema.md §1, schema.sql header)

| Database | Owner Service | Aggregates persisted | Port (local) |
|---|---|---|---|
| `sales_db` | SaleService | `FlashSale`, `SaleSchedule`, `SaleStatusHistory` | 5432 |
| `inventory_db` | InventoryService | `Product`, `StockLevel`, `Reservation`, `StockLog` | 5433 |
| `orders_db` | OrderService | `Order`, `OrderOutbox`, `IdempotencyRecord` | 5434 |
| ClickHouse (`flash_sale` DB) | AnalyticsService | `sale_events` wide columnar table | HTTP 8123 / TCP 9000 |

Engine: PostgreSQL 16+. All PKs are `UUID` (`gen_random_uuid()`), never `BIGSERIAL` (DD-001). All timestamps are `TIMESTAMPTZ`, never naive `TIMESTAMP` (DD-005). Every mutable aggregate root has a `version BIGINT` optimistic-lock column (DD-004).

**Zero cross-database foreign keys.** Cross-service references are opaque UUIDs; referential integrity is maintained via Kafka events and saga compensation, never database constraints (DD-003).

### 8.2 `sales_db` tables

| Table | Purpose | Notable constraints |
|---|---|---|
| `flash_sales` | `FlashSale` aggregate root | `status IN (4 valid values)`; `total_stock > 0`; `activated_at` NULL iff `SCHEDULED` |
| `sale_schedules` | `SaleSchedule` entity (1:1 with `flash_sales`) | `sale_end > sale_start`; one schedule per sale (UNIQUE) |
| `sale_status_history` | Immutable, INSERT-only audit log | Satisfies PRD US-004 / NFR-023 |

### 8.3 `inventory_db` tables

| Table | Purpose | Notable constraints |
|---|---|---|
| `products` | `Product` aggregate root (global product def, does not know about sales) | `sku` UNIQUE; `total_stock >= 0` |
| `stock_levels` | `StockLevel` entity — Postgres source of truth for stock (Redis is a projection) | `UNIQUE(product_id, sale_id)`; `current_stock <= total_allocated`; `current_stock >= 0` |
| `reservations` | `Reservation` aggregate root | **Partial unique index** `(user_id, sale_id) WHERE status IN ('PENDING','CONFIRMED')` enforces "one active reservation per user per sale" at the DB level; `idempotency_key` UNIQUE |
| `stock_reservation_log` | Append-only audit table for reconciliation/fraud detection; never read on hot path | `operation IN (RESERVE, RELEASE, RECONCILE, EXPIRE)` |

### 8.4 `orders_db` tables

| Table | Purpose | Notable constraints |
|---|---|---|
| `orders` | `Order` aggregate root | `idempotency_key` UNIQUE (**the core correctness guarantee**); `reservation_id` UNIQUE; `amount > 0` |
| `order_outbox` | `OutboxEvent` entity — most operationally critical table; polled every 500ms | `event_id` UNIQUE (Kafka dedup key); partial index `WHERE published = FALSE` |
| `idempotency_keys` | `IdempotencyRecord` entity — durable fallback for Redis idempotency cache | PK is the key itself; `expires_at` is a `GENERATED ALWAYS AS (created_at + INTERVAL '24 hours')` column |

### 8.5 Notable query patterns (DatabaseSchema.md §8)

- **Outbox poller** (every 500ms, batches of 100): `SELECT ... WHERE published = FALSE ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED`. `SKIP LOCKED` (not plain `FOR UPDATE`) is mandatory — it lets multiple OrderService pods run the poller concurrently without queuing (DD-008).
- **Stock fallback** (Redis down or miss): `SELECT ... FOR UPDATE` then conditional `UPDATE ... WHERE version = $expected`. Belt-and-suspenders: row lock + optimistic check.
- **Expiry sweep** (every 30s): `UPDATE reservations SET status='EXPIRED' WHERE status='PENDING' AND expires_at < NOW() RETURNING ...` — return values drive `StockReleased` events.
- **Sale activation sweep** (every 5s, per Build-Plan Week 7): `UPDATE flash_sales SET status='ACTIVE' ... WHERE status='SCHEDULED' AND version=$expected` — 0 rows returned means another pod already activated it; safe to ignore.

### 8.6 `schema.sql` cross-check

`schema.sql` (570 lines) is the literal executable form of §8.2–8.4: same three databases (`\connect sales_db` / `inventory_db` / `orders_db`), same 9 tables, same constraint and index names as `DatabaseSchema.md`. It additionally defines a shared `set_updated_at()` trigger function applied via `BEFORE UPDATE` triggers to every table that has an `updated_at` column (`flash_sales`, `sale_schedules`, `products`, `stock_levels`, `reservations`, `orders` — not `order_outbox` or `idempotency_keys`, which have no `updated_at` column).

---

## 9. Kafka Design

### 9.1 Topics — as defined in the ranked/ADR sources (Final-Spec-Council.md §3, 01-Decisions.md Decisions 007 & 017, README.md)

| Topic | Partitions | Partition Key | Retention | Producer | Consumers |
|---|---|---|---|---|---|
| `sale-events` | 8 | `saleId` | 7 days | SaleService | NotificationService, AnalyticsService |
| `inventory-events` | 16 | `productId` | 3 days | InventoryService | OrderService, NotificationService, AnalyticsService |
| `order-events` | 8 | `saleId` | 3 days | OrderService | NotificationService, AnalyticsService |
| `notifications.dlq` | 4 | none | 14 days | NotificationService | ops replay tool |
| `analytics.dlq`* | 4 | none | 14 days | AnalyticsService | ops replay tool |

\* `analytics.dlq` appears in 01-Decisions.md Decision 017's topic-ownership table and in README.md's `make kafka-create-topics` output, but is **absent** from Final-Spec-Council.md §3's topic table (which lists only `notifications.dlq`). See §15.

**Why `inventory-events` uses `productId`, not `saleId` (ADR-013 / Decision 007):** Two concurrent reservations for the same product must be processed sequentially by the OrderService consumer to guarantee correct stock confirmation. Keying by `saleId` would scatter same-product events across partitions and break per-product ordering. `sale-events` and `order-events` use `saleId` to preserve sale-level saga ordering instead.

**Cardinal rule (repeated verbatim across Final-Spec-Council.md, KafkaDesign.md, 01-Decisions.md):** Kafka is async fan-out only — never synchronous RPC. A service needing an immediate answer calls an HTTP endpoint; a service notifying others of a state change publishes an event.

**Binding Kafka rules (01-Decisions.md Decision 017):**
1. `AUTO_CREATE_TOPICS_ENABLE=false` — every topic is explicitly owned by a service and created via a `KafkaTopicConfig @Bean`.
2. `enable.auto.commit=false` on all consumers — offsets committed only after successful processing.
3. Producer config: `acks=all`, `enable-idempotence=true` — no message loss on broker failover.
4. Apache Kafka 3.7.0 in KRaft mode (no Zookeeper).

### 9.2 Consumer groups named in the ranked sources

| Consumer Group | Consumes | Service | Source |
|---|---|---|---|
| `order-svc-reservation-consumer` | `inventory-events` | OrderService | PRD FR-022; 01-Decisions.md Decision 007 impact note |
| `notification-svc-consumer` | `sale-events`, `inventory-events`, `order-events` | NotificationService | Final-Spec-Council.md §2.4, PRD FR-024 |
| `analytics-svc-consumer` | `sale-events`, `inventory-events`, `order-events` | AnalyticsService | Final-Spec-Council.md §2.5, PRD FR-030 |

**Note:** KafkaDesign.md and Build-Plan.md instead use the name `order-svc-inventory-consumer` for OrderService's `inventory-events` consumer group. This naming conflict is logged in §15.

### 9.3 Retry topics and DLQ (KafkaDesign.md — not present in the ranked sources)

KafkaDesign.md defines `sale-events.retry`, `inventory-events.retry`, and `order-events.retry` topics (same partition counts as their parent topics, 1-day retention) with a retry/backoff scheme, and Build-Plan.md Week 8 builds against this design (retry consumers, `RetryPublisher`/`DlqPublisher`, error classification into `RETRIABLE` vs `TERMINAL`). Retry backoff schedules per KafkaDesign.md §5:

| Topic family | Max attempts (main) | Max attempts (`.retry`) | Backoff | Window before DLQ |
|---|---|---|---|---|
| `sale-events` | 1 | 3 | 1s, 8s, 32s | ~41s |
| `inventory-events` | 1 | 5 | 1s, 4s, 16s, 64s, 256s | ~341s (~5.7 min) |
| `order-events` | 1 | 3 | 1s, 8s, 32s | ~41s |

These retry topics are **not mentioned** in Final-Spec-Council.md, the ADR log, or README.md's topic list. See §15.

### 9.4 Producer/consumer configuration (KafkaDesign.md §7–8)

- Producer: `acks=all`, `retries=3`, `enable-idempotence=true`, `max-in-flight-requests-per-connection=5`, `batch-size=16384`, `linger-ms=5`, `compression-type=lz4`.
- Consumer: `auto-offset-reset=earliest`, `enable-auto-commit=false`, `isolation-level=read-committed`.
- OrderService (saga-critical) commits **individually per message**, not by batch, to bound reprocessing scope on crash.
- NotificationService / AnalyticsService (idempotent, non-critical path) commit in batches (every 100 messages or 500ms).
- OrderService's outbox-poller producer is explicitly **not** wrapped in a Kafka transaction — the Postgres transaction is the atomicity boundary; the outbox pattern already guarantees at-least-once delivery.

---

## 10. Redis Design

### 10.1 Cluster topology (01-Decisions.md Decision 016, confirmed by README.md, RedisDesign.md)

- Redis Cluster mode: **3 primary shards, 1 replica per shard, 6 nodes total**, bootstrapped by a 7th one-shot init container.
- Hash-slot sharding: `CRC16(key) % 16384`.
- Hash tags (e.g. `{saleId}`) force related keys onto the same shard — required for any multi-key Lua script.
- Eviction: `allkeys-lru`. Persistence: AOF `appendfsync everysec` (≤1s data loss acceptable — Postgres is the durable source of truth).
- `cluster-node-timeout`: 5000ms (replica promotes to primary within this window on primary failure).
- Keyspace notifications: `Ex` enabled (needed for reservation-expiry callbacks without polling).

**Cardinal rule (repeated across Final-Spec-Council.md, RedisDesign.md, 01-Decisions.md):** Redis is never the source of truth. Every Redis key has a Postgres fallback. A total Redis failure must degrade performance, not corrupt data.

### 10.2 The "Three-Layer Contract" (Final-Spec-Council.md §4, ADR Decision 011 — ranked/authoritative version)

| Layer | Owner | Key Pattern | Structure | TTL | Fallback |
|---|---|---|---|---|---|
| 1 — Stock counter | InventoryService | `stock:{saleId}` | String (int) | `saleEnd + 10 min` | `SELECT FOR UPDATE` in Postgres |
| 2 — Rate limiter | API Gateway / SaleService | `rate:{userId}:{window_minute}` | Sorted Set | 60s | Fail-open + audit log |
| 3 — Session & Idempotency | OrderService | `session:{userId}`, `idem:{idempotencyKey}` | Hash / String | 5min / 24h | Postgres lookup |

Stock-counter Lua script (Final-Spec-Council.md §2.2 / ADR Decision 003):
```
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -2 end   -- cache miss → fallback to Postgres
if stock <= 0   then return -1 end   -- sold out
redis.call('DECR', KEYS[1])
return stock - 1
```
Return codes: `-2` = cache miss, `-1` = sold out, `>= 0` = success.

### 10.3 RedisDesign.md's expanded 5-layer version (supplementary, unranked document)

RedisDesign.md restates the above as 5 layers by splitting "Session & Idempotency" into two and adding a fifth layer for sale metadata:

| Layer | Key Pattern | TTL |
|---|---|---|
| 1 — Stock counter | `stock:{saleId}` | `saleEnd + 10 min` |
| 2 — Rate limiter | `rate:{userId}:{saleId}` | 60s |
| 3 — Session cache | `session:{userId}` | 5 min, rolling |
| 4 — Idempotency cache | `idem:{userId}:{idempotencyKey}` | 24h, fixed (never extended) |
| 5 — Sale metadata cache | `sale:meta:{saleId}` (Hash), `sale:active:{saleId}` (String flag) | `saleEnd + 10 min` / `saleEnd + 30s` |

This introduces two direct conflicts with the ranked Layer-2/Layer-3 key patterns above (rate-limiter key scope, idempotency key scope) — see §15.

Additional keys named only in RedisDesign.md: `resv:lock:{userId}:{saleId}` (30s duplicate-reservation guard, also used in Build-Plan Week 4) and `stock:warmed:{saleId}` (pre-warm completion marker, `SET NX` guarded).

### 10.4 Fallback behavior per layer (RedisDesign.md §9, consistent with PRD Error/Edge-case sections)

| Layer | On Redis miss/unavailable |
|---|---|
| Stock counter | Load from `stock_levels` in Postgres, re-warm Redis, retry once. Circuit breaker opens after **5 consecutive failures** (PRD ERR-002) before routing to `SELECT FOR UPDATE`. |
| Rate limiter | **Fail open** (allow the request) — a rate-limiter outage must not block a sale. Audit-logged. |
| Session | Validate JWT directly (stateless) — no Postgres lookup needed. |
| Idempotency | Query `idempotency_keys` table in Postgres; re-warm Redis on hit. |
| Sale metadata | Query `flash_sales` + `sale_schedules` from `sales_db`; repopulate cache. |

### 10.5 Stampede protection

Stock counter and sale-active flag are pre-warmed at `T-60s` before `saleStart` (FR-006). A probabilistic early-refresh backstop (XFetch-style) is documented in RedisDesign.md §10 for keys that miss the pre-warm window.

---

## 11. API Surface (Final-Spec-Council.md §2, PRD §4)

| Endpoint | Owner | Notes |
|---|---|---|
| `POST /api/v1/sales` | SaleService | Create sale; required fields `productId`, `totalStock`, `saleStart`, `saleEnd`, `name` (FR-001) |
| `GET /api/v1/sales/{id}` | SaleService | Cached in Redis |
| `PATCH /api/v1/sales/{id}/status` | SaleService | Admin force-transition |
| `GET /api/v1/sales/{id}/active` | SaleService | Hot path — **must** be served from Redis; never touches Postgres while sale is `ACTIVE` (FR-007, NFR-003: ≤10ms P99) |
| `GET /api/v1/sales/{id}/history` | SaleService | Immutable audit trail (FR-008, US-004) |
| `POST /api/v1/reservations` | InventoryService | Requires `Idempotency-Key`; returns `201`/`409 SOLD_OUT`/`409 SALE_NOT_ACTIVE`/`429` (US-005) |
| `POST /api/v1/orders` | OrderService | Requires `Idempotency-Key` (400 if missing, FR-016); returns `202 Accepted` immediately, processed async (US-006, NFR-002: ≤100ms P99) |

All mutating endpoints require and propagate a `traceId` (UUID v4) via `X-Trace-Id` (FR-034). All endpoints require a valid bearer token validated at the API gateway; services trust the gateway-propagated `userId` header and do not re-validate tokens (NFR-019).

---

## 12. Non-Functional Requirements (PRD §5 — summarized by category)

| Category | Key targets |
|---|---|
| Performance | Reservation P99 ≤ 50ms at 50k concurrent users (NFR-001); Order `202` P99 ≤ 100ms (NFR-002); `GET /active` P99 ≤ 10ms (NFR-003); Analytics lag ≤ 5s (NFR-004); Notification dispatch ≤ 30s (NFR-005) |
| Scalability | Horizontal scale via K8s HPA, no in-process state blocking multi-replica deploys (NFR-006); consumer parallelism matches partition count (NFR-007); Redis 3 primary shards (NFR-008) |
| Availability | InventoryService/OrderService/SaleService: 99.9% monthly; NotificationService/AnalyticsService: 99.5% (NFR-010); Postgres-fallback degraded mode preserves correctness over availability (NFR-011); Notification/Analytics outages have zero impact on transactional flows (NFR-012/013); graceful `SIGTERM` handling — finish in-flight (≤30s), commit offsets, flush outbox (NFR-014) |
| Consistency | Oversell rate = 0 (NFR-015); duplicate order rate = 0 (NFR-016); at-least-once event delivery with idempotent consumers (NFR-017); every saga step has a compensating transaction, escalated to DLQ after 3 retries (NFR-018) |
| Security | Bearer-token auth at gateway only (NFR-019); 10 req/user/min rate limit via Redis sliding window (NFR-020); reject malformed input with `400` before it reaches business logic (NFR-021); secrets in Kubernetes Secrets only, never env vars/config files/logs (NFR-022); append-only audit log for admin actions (NFR-023) |
| Observability | Prometheus metrics incl. request/error rate, P50/95/99 latency, JVM heap, GC pause, Kafka lag, Redis latency (NFR-024); OpenTelemetry spans, 100%/10% sampling (error/success) (NFR-025); structured JSON logs with `traceId`/`saleId`/`userId` (NFR-026); alerting thresholds table (NFR-027, reproduced in §9 of PRD and matching README/Build-Plan) |
| Testability | ≥80% line coverage on business logic (NFR-028); Testcontainers-based integration tests (NFR-029); Gatling/k6 load test at 50k concurrent users (NFR-030); jqwik property-based tests on the Lua script (NFR-031) |

### 12.1 Acceptance criteria summary (PRD §8)

| ID | Requirement | Target |
|---|---|---|
| AC-001 | Reservation P99 latency | ≤ 50ms at 50k concurrent users |
| AC-002 | Oversell rate | 0 under all conditions |
| AC-003 | Order idempotency | 0 duplicate orders on retry |
| AC-004 | Event delivery | At-least-once; 0 lost events on broker recovery |
| AC-005 | Sale start accuracy | Within ± 5 seconds of `saleStart` |
| AC-006 | Notification latency | ≤ 30s post-`OrderCreated` |
| AC-007 | Analytics lag | ≤ 5s under normal load |
| AC-008 | Redis fallback correctness | Zero oversell when Redis unavailable |
| AC-009 | Saga compensation | Stock restored within 30s of `PaymentFailed` |
| AC-010 | Rate limiting | 429 on >10 req/min; no reservation leaks |
| AC-011 | Graceful shutdown | Zero in-flight request loss on `SIGTERM` |
| AC-012 | Pre-warm reliability | Stock counter live in Redis 60s before sale start |

---

## 13. Local Development Infrastructure (README.md)

### 13.1 Prerequisites

Docker Engine 24.0+, Docker Compose v2 (plugin — not the hyphenated v1 CLI), GNU Make 3.81+, redis-cli 7.x, psql 16.x, curl. First `make up` pulls ~3GB of images; Docker Desktop needs ≥6GB RAM allocated.

### 13.2 What runs locally

| Component | Detail |
|---|---|
| PostgreSQL | 3 fully separate instances/containers: `flash-sale-sales-db` (5432), `flash-sale-inventory-db` (5433), `flash-sale-orders-db` (5434). User `flashsale`, password `flashsale_dev` (dev-only, intentionally weak). |
| Redis Cluster | 6 node containers (ports 7001–7006, cluster bus 17001–17006) + 1 one-shot `flash-sale-redis-cluster-init` container that forms the cluster and exits (`Exited (0)` is correct/expected). Password `redis_dev`. |
| Kafka | Single broker, KRaft mode. Internal listener `kafka:29092` (for containers), external listener `localhost:9092` (for host/IDE), KRaft controller `localhost:9093` (internal-only, do not use directly). Single broker is stated as correct for local dev; production uses 3 brokers with replication. |
| ClickHouse | HTTP 8123, TCP 9000. `flash_sale` database and `sale_events` table pre-created on first start. User `flashsale`, password `flashsale_dev`. |
| Kafka UI | http://localhost:18080 |
| RedisInsight | http://localhost:18081 |

### 13.3 Key `make` targets

`make up` / `make down` / `make restart` / `make health` / `make ps` / `make logs` / `make clean` (wipes volumes) / `make nuke` (wipes volumes + images) / `make db-sales` / `make db-inventory` / `make db-orders` / `make kafka-topics` / `make kafka-lag` / `make kafka-create-topics` / `make redis-cluster-info` / `make redis-check` / `make redis-stock-watch SALE_ID=`.

`make kafka-create-topics` creates exactly 5 topics: `sale-events` (8), `inventory-events` (16), `order-events` (8), `notifications.dlq` (4), `analytics.dlq` (4) — no `.retry` topics.

### 13.4 Health check expectations

Postgres: `pg_isready` → "accepting connections." Redis: `CLUSTER INFO` → `cluster_state:ok`, `cluster_known_nodes:6`, `cluster_size:3`. Kafka: `kafka-broker-api-versions.sh` → no ERROR lines. ClickHouse: `curl :8123/ping` → `Ok.`. `lock_timeout` should read 5000ms on `inventory-db`, 0 on the other two; `timezone` should be UTC on all three.

### 13.5 Documented troubleshooting scenarios

`make up` hanging (image pull / OOM); Postgres `Exited (1)` (port conflict or corrupt volume); Redis `cluster_state:fail` (most commonly laptop hibernation — nodes disconnect on sleep); Kafka `Exited (1)` (stale cluster ID metadata, or port 9092 conflict); ClickHouse slow to become healthy (20–30s startup); IDE-run services needing `localhost` instead of container names for Postgres/Redis/Kafka; disk space exhaustion (`docker system prune`).

---

## 14. Build Plan (Build-Plan.md — 10-week roadmap, "Approved for execution")

**Rule stated by the plan's owner:** each week must produce working, runnable software; a green test suite is the only definition of "done."

| Week | Phase | Goal |
|---|---|---|
| 1 | Foundation | Full infra stack runnable locally with one command; Flyway migrations auto-applied; seed data present |
| 2 | Core Services | SaleService skeleton; `FlashSale` aggregate with Java 21 sealed-interface state machine; unit-tested transitions |
| 3 | Core Services | InventoryService stock counter; Lua decrement live; Postgres fallback tested by killing Redis; property-based tests prove stock never goes negative |
| 4 | Core Services | `Reservation` aggregate live; partial unique index enforces one-active-reservation-per-user-per-sale; expiry sweep releases stock |
| 5 | Core Services | OrderService core + Transactional Outbox; retry with same `Idempotency-Key` returns original response, no duplicate |
| 6 | Integration | End-to-end Kafka wiring: outbox poller + saga consumer; `InventoryEventTranslator` ACL in place — **highest-risk week**, first time all 3 transactional services interact |
| 7 | Integration | `GET /active` served from Redis with zero Postgres queries on hot path; rate limiter enforced; 10k RPS load test |
| 8 | Resilience | Retry/DLQ classification (`RETRIABLE` vs `TERMINAL`); NotificationService dispatch stubs; chaos test — kill Kafka mid-sale, verify zero event loss |
| 9 | Observability | AnalyticsService batch-writes to ClickHouse within 5s; Prometheus metrics on all services; OpenTelemetry `traceId` propagation across Kafka headers |
| 10 | Production Readiness | Helm charts + correct HPA for all 5 services; Gatling load test at 50k concurrent users, P99 <50ms, zero oversells; full ADR interview dry-run |

**Summary metrics (Build-Plan.md):** 10 weeks, 5 services, 38 total tasks, 0 blocked weeks, 0 weeks depend on not-yet-completed work.

**Current implementation status: none.** No service code, Helm charts, or CI exists yet in this repository as of this document's generation — everything reviewed here is specification, ADRs, domain modeling, schema design, and planning.

---

## 15. Known Conflicts — Flagged, Not Resolved

Per `AI-CONTEXT.md`: *"If documentation conflicts, stop and ask."* The following are genuine, verified inconsistencies found across the repository's own documents. None has been resolved here — each needs an explicit decision (and, presumably, a new or amended ADR) before implementation proceeds on the affected area.

| # | Topic | Conflict | Sources |
|---|---|---|---|
| 1 | **Rate-limiter Redis key scope** | Ranked docs key by time window: `rate:{userId}:{window_minute}`. Unranked/supplementary docs key by sale: `rate:{userId}:{saleId}`. These scope the limiter differently (global-per-minute vs. per-sale). | Final-Spec-Council.md §4 / 01-Decisions.md Decision 011 **vs.** RedisDesign.md §2/§5 and README.md §11 troubleshooting examples |
| 2 | **Idempotency Redis key scope** | Ranked docs (and FR-017) use `idem:{idempotencyKey}` with no user scoping. PRD's own edge case EC-007, plus RedisDesign.md and Build-Plan Week 5, use `idem:{userId}:{idempotencyKey}`. | Final-Spec-Council.md §4 / 01-Decisions.md Decision 009 / PRD FR-017 **vs.** PRD EC-007 / RedisDesign.md §7 / Build-Plan.md task 5.3 |
| 3 | **Redis memory cap: per-shard or cluster-wide** | ADR Decision 016 states "4 GB per shard" (12GB cluster total across 3 shards). RedisDesign.md states "4 GB total (≈1.3 GB/shard)." These are different numbers by 3x. | 01-Decisions.md Decision 016 **vs.** RedisDesign.md §1 |
| 4 | **Kafka UI port number** | README.md (rank 1) states the Kafka UI runs at `localhost:18080`. Build-Plan.md Week 1 states `localhost:8080` in both its objectives and Definition-of-Done checklist. | README.md §2/§3/§11 **vs.** Build-Plan.md Week 1 |
| 5 | **OrderService's `inventory-events` consumer-group name** | PRD FR-022 and the ADR-007 impact note name it `order-svc-reservation-consumer`. KafkaDesign.md and Build-Plan.md consistently use `order-svc-inventory-consumer`. | PRD FR-022 / 01-Decisions.md Decision 007 **vs.** KafkaDesign.md §3/§5 / Build-Plan.md |
| 6 | **README.md's own container count** | The README states "the stack has 17 containers" (twice, in the intro and in §5). Its own itemized breakdown in §2 ("What is running") enumerates 3 Postgres + 6 Redis nodes + 1 Redis init container + 1 Kafka broker + 1 ClickHouse + 2 UI containers = **14**, not 17. This is an internal inconsistency within the single highest-priority document. | README.md §1 (intro), §2, §5 |
| 7 | **`analytics.dlq` topic — present in the "final" spec or not** | Final-Spec-Council.md §3's topic table (the definitive architecture spec) lists only `notifications.dlq`. 01-Decisions.md Decision 017 and README.md's `make kafka-create-topics` both include `analytics.dlq` alongside it. Likely just an omission in the Final-Spec-Council table rather than a true design disagreement, but the ranked "FINAL" document is incomplete relative to the ADR log and README. | Final-Spec-Council.md §3 **vs.** 01-Decisions.md Decision 017 / README.md §10 |
| 8 | **Kafka retry topics (`*.retry`) — undocumented in the ranked architecture** | `sale-events.retry`, `inventory-events.retry`, and `order-events.retry` are defined in detail in KafkaDesign.md and actively built against in Build-Plan.md Week 8, but do not appear anywhere in Final-Spec-Council.md, the ADR log, or README.md's topic list. Not a direct contradiction, but a material architectural element with no standing in any ranked/authoritative source. | KafkaDesign.md §1/§5 / Build-Plan.md Week 8 **vs.** absence from Final-Spec-Council.md, 01-Decisions.md, README.md |
| 9 | **RedisDesign.md's 5-layer model vs. the ADR's 3-layer contract** | ADR Decision 011 defines exactly 3 Redis layers (Stock / Rate-limiter / Session+Idempotency combined). RedisDesign.md restructures this into 5 layers by splitting Session and Idempotency apart and adding a fifth "Sale Metadata" layer. Content-wise this is mostly an elaboration (Sale Metadata caching is implied elsewhere in Final-Spec-Council.md as "SaleService caches active sale metadata"), but the layer *numbering and count* itself conflicts with the ADR, and it's the vehicle through which conflicts #1 and #2 above are introduced. | 01-Decisions.md Decision 011 **vs.** RedisDesign.md §1–8 |
| 10 | **No per-service application port numbers defined** | Not a contradiction but a gap: no document assigns an HTTP port to any of the 5 Spring Boot services individually (only infrastructure ports — Postgres/Redis/Kafka/ClickHouse/UIs — are specified). One incidental example (`localhost:8081/actuator/prometheus` in Build-Plan.md Week 9) is not tied to a named service and shouldn't be treated as a real assignment. | Absence across all documents; incidental example in Build-Plan.md Week 9 |

---

## 16. Source File Manifest

| File | Role |
|---|---|
| `AI-CONTEXT.md` | Meta-document: defines source-of-truth precedence and the "never invent / stop on conflict" rules used to build this PROJECT.md |
| `README.md` | Rank 1 — local infrastructure operations guide (Docker Compose stack, ports, health checks, troubleshooting) |
| `PRD-FlashSalePlatform.md` | Rank 2 — product requirements: personas, user stories, functional/non-functional requirements, error scenarios, edge cases, acceptance criteria |
| `Final-Spec-Council.md` | Rank 3 — definitive architecture specification v2.0; supersedes a prior 3-service v1 design; records the contradiction and its resolution |
| `01-Decisions.md` | Rank 4 — 19 ADRs (Decisions 001–019) |
| `DomainModel.md` | Rank 5 — DDD model: aggregates, entities, value objects, bounded contexts, context map, event catalogue |
| `DatabaseSchema.md` | Rank 6 — PostgreSQL schema design, prose form, with query patterns and design-decision rationale |
| `schema.sql` | Rank 7 — literal executable PostgreSQL DDL for the three databases |
| `RedisDesign.md` | Unranked/supplementary — detailed Redis architecture, Lua scripts, Spring Boot implementation samples |
| `KafkaDesign.md` | Unranked/supplementary — detailed Kafka architecture, event schemas, retry/DLQ design, producer/consumer configuration |
| `Build-Plan.md` | Unranked/supplementary — 10-week implementation roadmap with weekly tasks, deliverables, and Definition of Done |

---

*This document contains no information beyond what is stated in the 11 files listed above. Where a fact could not be verified or where sources disagreed, that is stated explicitly rather than resolved by assumption.*