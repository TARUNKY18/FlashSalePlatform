# PROJECT_TRUTH.md
## Flash Sale Platform — Single Source of Truth
**Version:** 3 (replaces Version 2, 2026-06-17)

> **Implementation status warning (updated 2026-09-23):** Broad historical
> implementation-status fields in this document remain stale. The verified
> Slice 6 and Week 5 Slices 1–4 facts below have been reconciled; for complete current implementation
> truth, see `HANDOFF.md` and `context/CURRENT_STATE.md`. The architecture, ADR,
> and design sections remain authoritative. Do not copy any unreconciled
> "PLANNED — no code" field into implementation decisions.

**Status legend:**
- `VERIFIED` — confirmed via terminal output, docker logs, screenshots, or explicit command results.
- `PLANNED` — documented/designed but not yet implemented, applied, or confirmed.
- `NOT VERIFIED` — attempted or expected, outcome unconfirmed.

Unresolved documentation conflicts are never explained or resolved in this document. Every one is marked: **See CONFLICTS.md**

---

## Project Overview

**Name:** Flash Sale Platform
**Repository root:** `FlashSalePlatform/` — VERIFIED (`make up` runs from this directory)

**Purpose (PLANNED — documented in AI-CONTEXT.md):** Build a production-grade flash sale platform for learning distributed systems.

**Problem statement (PLANNED — documented in PRD-FlashSalePlatform.md §1.1):** Time-limited flash sales collapse demand into a narrow window, creating a thundering-herd problem. This causes overselling, degraded user experience (timeouts, duplicate orders), and loss of operational visibility.

**Product goal (PLANNED — documented in PRD-FlashSalePlatform.md §1.2):** Build a backend platform that runs flash sales at scale — correct inventory, fair allocation, idempotent ordering, and observable operations — without sacrificing latency under peak load.

**Personas / Actors (PLANNED — PRD §2):**
| Actor | Type | Goals |
|---|---|---|
| Buyer | End user | Reserve a product before sellout; get confirmation; not be double-charged on retry |
| Sale Administrator | Internal operator | Create/schedule sales, monitor stock, force-end a sale |
| Platform Operator (SRE / On-Call) | Internal operator | Real-time visibility, alerting, fast incident diagnosis |
| Scheduler | System actor | Time-based trigger transitioning sale status |

**Source documents (precedence order per AI-CONTEXT.md):** `README.md` > `PRD-FlashSalePlatform.md` > `Final-Spec-Council.md` > ADRs (`01-Decisions.md`) > `DomainModel.md` > `DatabaseSchema.md` > `schema.sql`. `Build-Plan.md`, `RedisDesign.md`, `KafkaDesign.md` are unranked supplementary documents.

**Reviewers / Council (PLANNED — named in PRD, Final-Spec-Council, DomainModel, ADRs):** Jordan Wei (Google), Priya Nair (Uber), Marcus Shaw (Amazon), Elena Kovac (Atlassian), plus a Staff Engineer mentor.

---

## Business Rules

**Status: PLANNED — none of the following is enforced by running code. No Java code exists in the repository.**

**In scope for v1.0:** flash sale creation/scheduling/lifecycle; concurrent inventory reservation with atomic stock control; idempotent order creation with saga-based consistency; async notifications (email/push/SMS); real-time analytics event ingestion.

**Out of scope for v1.0:** payment processing (stubbed); user authentication/identity (assumed provided upstream); product catalog management (products pre-loaded); customer-facing storefront.

**Core correctness rules:**
- Oversell rate must be 0 under all conditions (NFR-015 / AC-002).
- Duplicate order rate must be 0 (NFR-016 / AC-003).
- `Idempotency-Key` header is required on all mutating endpoints; missing key → `400 Bad Request`.
- Every saga step that can fail must have a defined compensating transaction; escalate to dead-letter topic after 3 retries (NFR-018).
- All Kafka events are delivered at-least-once; consumers must be idempotent via `eventId` deduplication (NFR-017).
- A reservation TTL defaults to 10 minutes; a user may hold only one active reservation per sale.
- Sale state machine: `SCHEDULED → ACTIVE → ENDED → ARCHIVED`; no state may be skipped; no reverse transition except `ACTIVE → ENDED` via admin.
- Rate limit: 10 requests per user per minute on reservation endpoints (NFR-020). Key-scoping of this limit differs across documents — See CONFLICTS.md.

**Non-functional requirement categories:**
| Category | Key targets |
|---|---|
| Performance | Reservation P99 ≤ 50ms at 50k concurrent users; Order `202` P99 ≤ 100ms; `GET /active` P99 ≤ 10ms; Analytics lag ≤ 5s; Notification dispatch ≤ 30s |
| Scalability | Horizontal scale via K8s HPA; consumer parallelism matches partition count; Redis 3 primary shards |
| Availability | InventoryService/OrderService/SaleService: 99.9% monthly; NotificationService/AnalyticsService: 99.5%; graceful `SIGTERM` handling |
| Consistency | Oversell rate = 0; duplicate order rate = 0; at-least-once delivery; compensating transactions mandatory |
| Security | Bearer-token auth at gateway only; 10 req/user/min rate limit; input validation before business logic; secrets in Kubernetes Secrets only |
| Observability | Prometheus metrics; OpenTelemetry spans (100% sampling on error, 10% on success); structured JSON logs |
| Testability | ≥80% line coverage; Testcontainers integration tests; Gatling/k6 load test at 50k users; jqwik property-based tests on the Lua script |

**Acceptance criteria:**
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

## Architecture

**5-service model (PLANNED — documented):** SaleService, InventoryService, OrderService, NotificationService, AnalyticsService. Supersedes an earlier 3-service design. Hard rules: zero cross-service database joins; no synchronous HTTP calls on the reserve→order hot path (Kafka only); each service independently deployable with its own Dockerfile, Helm chart, and HPA config.

**Architecture Decision Records (01-Decisions.md, 19 total) with implementation status:**

| # | Title | Design Status | Implementation Status |
|---|---|---|---|
| 001 | Service Boundaries (5 services; = ADR-009) | Approved | PLANNED — 0 of 5 services written |
| 002 | Database Ownership — Database-per-Service (= ADR-008) | Approved | VERIFIED — enforced at infrastructure level; 3 separate Postgres containers running |
| 003 | Stock Decrement — Redis Lua Atomic Script (= ADR-001) | Approved | PLANNED — 4 Lua scripts generated; no service exists to execute them |
| 004 | Redis Fallback — `SELECT FOR UPDATE` | Approved | PLANNED — no code |
| 005 | Concurrency Model — Java 21 Virtual Threads (= ADR-002) | Approved | PLANNED — no Java code exists |
| 006 | Inter-Service Communication — Kafka async / HTTP sync (= ADR-003) | Approved | PARTIAL — InventoryService produces `StockReserved` and `ReservationExpired`; no consumers exist |
| 007 | Kafka Topic Design and Partition Strategy (= ADR-013) | Approved | PARTIAL — InventoryService owns creation of `inventory-events`; other topics remain planned |
| 008 | Event Reliability — Transactional Outbox (= ADR-004) | Approved | PARTIAL — InventoryService publishes its outbox; OrderService atomically persists `order_outbox`, with publication deferred to Week 6 |
| 009 | Order Idempotency — Dual-Layer Key Check | Approved | PARTIAL — PostgreSQL authority and best-effort Redis cache are implemented; HTTP integration remains task 5.5 |
| 010 | Saga Pattern — Choreography over Orchestration (= ADR-012) | Approved | PLANNED — no saga consumers exist |
| 011 | Redis Architecture — Three-Layer Contract | Approved | PLANNED — no code |
| 012 | Analytics Storage — ClickHouse over PostgreSQL (= ADR-011) | Approved | VERIFIED — ClickHouse container running and healthy |
| 013 | SaleService — Separate Bounded Context (= ADR-010) | Approved | PLANNED — no code |
| 014 | NotificationService — Stateless Async Consumer | Approved | PLANNED — no code |
| 015 | Contradiction Resolution — retires ADR-005 | Approved | N/A — documentation-only decision |
| 016 | Redis Cluster — Topology, Persistence, Eviction Policy | Approved | VERIFIED — 6 node containers healthy; `cluster_state:ok`, `cluster_known_nodes:6`, `cluster_size:3` confirmed 2026-06-17 |
| 017 | Kafka — Event-Driven Architecture, KRaft Mode, Topic Ownership | Approved | VERIFIED — broker healthy; `make health` returned `✓ Kafka broker reachable` confirmed 2026-06-17 |
| 018 | ClickHouse — Analytics Storage Engine | Approved | VERIFIED — ClickHouse container running, HTTP health check passed |
| 019 | PostgreSQL — Database-per-Service with Isolated Instances | Approved | VERIFIED — 3 separate Postgres 16.3-alpine instances running, `pg_isready` passed on all 3 |

**Technology stack:**
| Layer | Technology | Design detail | Runtime detail |
|---|---|---|---|
| Language / runtime | Java 21 | Virtual Threads, sealed interfaces, records | VERIFIED — SaleService, InventoryService, and OrderService inherit Java 21; virtual threads are enabled |
| Web framework | Spring Boot 3.3.4 | `spring.threads.virtual.enabled=true` | VERIFIED — Spring Boot 3.3.4; SaleService (8081), InventoryService (8082), and OrderService (8083) |
| Build tool | Gradle 8.10 wrapper | multi-module Groovy DSL | VERIFIED — `settings.gradle` includes `sale-service`, `inventory-service`, and `order-service`; the relevant Task 5.4 OrderService build succeeded |
| Messaging | Apache Kafka 3.7.0, KRaft mode | `AUTO_CREATE_TOPICS_ENABLE=false`, `enable.auto.commit=false`, `acks=all` | VERIFIED — `apache/kafka:3.7.0` running; `make health` returned `✓ Kafka broker reachable` confirmed 2026-06-17 |
| Cache / in-memory | Redis Cluster, 3 primary shards / 1 replica each | AOF `everysec`, `allkeys-lru` | VERIFIED — `redis:7.2.5-alpine`, 6 nodes healthy, `cluster_state:ok` confirmed 2026-06-17 |
| Relational DB | PostgreSQL 16 | 3 fully isolated instances | VERIFIED running (`postgres:16.3-alpine`, all 3 `pg_isready`) |
| Analytics DB | ClickHouse 24.3 | `MergeTree`, partitioned by `toYYYYMM(occurred_at)`, 90-day TTL | VERIFIED running (`clickhouse/clickhouse-server:24.3.3-alpine`, HTTP ping `Ok.`) |
| Local orchestration | Docker Engine 24.x+, Docker Compose v2 | — | VERIFIED — all compose commands execute |
| Production orchestration | Kubernetes + Helm | Deployment/Service/HPA/ConfigMap/Secret per service | PLANNED — no Helm charts, no cluster, no manifests |
| IaC | Terraform | — | PLANNED — no `.tf` files written |
| Observability | Micrometer, Prometheus, OpenTelemetry, Tempo | `/actuator/prometheus`, 100%/10% trace sampling | PLANNED — no metrics or tracing code written |
| Load testing | Gatling or k6 | 50,000 concurrent user simulation | PLANNED — no simulation files written |
| Property-based testing | jqwik 1.9.0 | Applied to Product stock domain model | VERIFIED — 5 properties × 1,000 generated examples in InventoryService; test-only classpath |
| Integration testing | Testcontainers | Real Postgres/Redis/Kafka in tests | VERIFIED — InventoryService uses PostgreSQL 16.3, Redis 7.2, and a Kafka 3.7-compatible broker; Slice 5 includes real same-endpoint Kafka outage/recovery coverage, test-only Slice 6 proves the 1500-request/1000-unit reservation invariant through MockMvc, and all 10 Task 5.3/5.4 OrderService PostgreSQL tests passed in a Docker-backed run |
| Migrations | Flyway | — | VERIFIED — V1 in SaleService (`flash_sales`, `sale_schedules`, `sale_status_history`); InventoryService V1 (`products`, `stock_levels`), V2 (`reservations`, `stock_reservation_log`), V3 (`idempotency_key NOT NULL`), V4 (`inventory_outbox`); OrderService V1 (`idempotency_keys`) and V2 (`orders`, `order_outbox`) |

**Package structure:** `com.flashsale.sale`, `com.flashsale.inventory`, and
`com.flashsale.order` exist. OrderService currently has its bootstrap, Order
domain, Task 5.3 idempotency application ports/service, and PostgreSQL/Redis
adapters, plus Task 5.4 Order/outbox persistence; its API and later-slice
messaging infrastructure remain planned.
NotificationService and AnalyticsService packages remain planned.

---

## Services

**Status:** SaleService and InventoryService are complete through their current
milestones. OrderService is complete through Week 5 Slice 4. NotificationService
and AnalyticsService remain planned.

| Service | Owns | DB / Schema | Kafka Role | Redis Role | Port | Code Status |
|---|---|---|---|---|---|---|
| SaleService | Sale lifecycle, scheduling, status machine | `sales_db` | Producer: `sale-events` | Cache: active sale metadata | 8081 | COMPLETE — Week 2; FlashSale aggregate, REST API, Flyway V1, 16 tests |
| InventoryService | Stock levels, atomic decrement, pre-warm, reservations | `inventory_db` | Producer: `inventory-events` | Layer 1: stock counter (Lua DECR), pre-warm | 8082 | COMPLETE through Week 4 — Slices 1–6 complete; 70 production Java files, 319 tests, latest implementation commit `8a60df7` |
| OrderService | Order lifecycle, idempotency, saga orchestration | `orders_db` | Producer: `order-events`; Consumer: `inventory-events` | Layer 3: idempotency key cache | 8083 | PARTIAL — Slices 1–4 complete; bootstrap, Order domain, PostgreSQL-authoritative idempotency, and atomic Order/outbox persistence; no orders API or messaging yet |
| NotificationService | Email, push, SMS fan-out | None (stateless) | Consumer: all three topics | None | 8084 | PLANNED — zero code written |
| AnalyticsService | Event ingestion, metrics, dashboards | ClickHouse | Consumer: all three topics | None | 8085 | PLANNED — zero code written |

Port numbers (8081–8085) are not present in the ranked architecture documents; they are carried here as previously recorded infrastructure notes only.

**Kubernetes resource baselines (PLANNED):**
| Service | CPU Request | CPU Limit | Memory Request | Memory Limit | Min Replicas | Max Replicas |
|---|---|---|---|---|---|---|
| SaleService | 250m | 1000m | 512Mi | 1Gi | 2 | 8 |
| InventoryService | 250m | 1000m | 512Mi | 1Gi | 2 | 10 |
| OrderService | 250m | 1000m | 512Mi | 1Gi | 2 | 10 |
| NotificationService | 100m | 500m | 256Mi | 512Mi | 1 | 5 |
| AnalyticsService | 100m | 500m | 256Mi | 512Mi | 1 | 5 |

HPA trigger: CPU utilisation > 70%.

---

## Domain Model

**Status: Reservation lifecycle production behavior through Slice 5 is
implemented and verified, including persistence, REST creation, expiry, and
application events backed by an infrastructure-owned outbox. Test-only Slice 6
verifies the 1500-request/1000-unit concurrency invariant without changing that
behavior. The Inventory domain model has no outbox state. The in-memory Order
aggregate, idempotency infrastructure, and atomic Order/outbox persistence are
implemented through Week 5 Slice 4; the API and later integration behavior
remain planned.**

**Aggregate roots (4):**
| Aggregate | Owning service / schema | Core invariant | State machine |
|---|---|---|---|
| `FlashSale` | SaleService / `sales_db` | Status transitions follow one path only; no reverse transition except `ACTIVE→ENDED` via admin | `SCHEDULED → ACTIVE → ENDED → ARCHIVED` |
| `Product` | InventoryService / `inventory_db` | Total allocated stock across all active sales must never exceed available stock | n/a |
| `Reservation` | InventoryService / `inventory_db` | Holds stock for exactly one user for a finite window; max one active reservation per user per sale | `PENDING → CONFIRMED / EXPIRED / RELEASED` |
| `Order` | OrderService / `orders_db` | Exactly one `Order` per `(UserId, idempotency key)` | `PENDING → CONFIRMED / CANCELLED / EXPIRED` |

**Implemented Order scope (Week 5 Slice 2):** `Order.place(...)` creates a
version-0 `PENDING` in-memory aggregate. Creation and transition times are
explicit `Instant` values. Manual `confirm`, `cancel`, and `expire` commands
transition only from `PENDING`; `CONFIRMED`, `CANCELLED`, and `EXPIRED` are
terminal, and rejected transitions do not mutate state. `cancel` requires a
non-null/nonblank reason without a fixed vocabulary; timeout maps to `expire`.
The Task 5.2 aggregate itself has no persistence, API, event, outbox, or
scheduler behavior.

**Implemented idempotency scope (Week 5 Slice 3):** `IdempotencyRecord` uses
`UserId` plus an opaque nonblank `String` key and stores an opaque serialized
response with a validated HTTP status. Lookup is Redis-first with PostgreSQL
fallback and best-effort Redis re-warming. Writes commit PostgreSQL first using
`INSERT ... ON CONFLICT DO NOTHING`, return the permanent existing record for a
duplicate, and populate Redis best-effort afterward. Redis keys are
`idem:{userId}:{idempotencyKey}` with a fixed 24-hour TTL that cache hits do not
extend. Default bootstrap startup does not require PostgreSQL or Redis.

**Implemented persistence scope (Week 5 Slice 4):** `Order.place(...)` creates
exactly one Order-owned initial `OrderCreated` event. The infrastructure
repository maps and persists the Order and its outbox row in one PostgreSQL
transaction, preserving the domain-generated Order, event, and outbox IDs.
`PurchaseIntentId` translates to `reservation_id` / `reservationId` only at
the persistence/event boundary. The outbox starts unpublished; Kafka,
publication, polling/locking, retry behavior, and HTTP work remain deferred.

**Entities:**
| Entity | Inside aggregate | Identity | Note |
|---|---|---|---|
| `SaleSchedule` | `FlashSale` | `scheduleId` | Immutable once sale is `ACTIVE` |
| `StockLevel` | `Product` | `productId + saleId` | Postgres is durable record; Redis is the fast projection |
| `OutboxEvent` | `Order` | `outboxEventId` | Written in the same transaction as the order |
| `IdempotencyRecord` | `Order` | `userId + idempotencyKey` | Permanent Postgres record + user-scoped Redis 24h cache |

**Value objects (Java 21 records with validating compact constructors):**
`SaleId`/`ProductId`/`OrderId`/`ReservationId`/`PurchaseIntentId`/`UserId`
(typed UUID wrappers), `SaleWindow` (`end` strictly after `start`), `StockCount`
(≥0), `Quantity` (≥1), `ReservationExpiry` (future at creation), and `Money`
(Order implementation requires amount > 0 and currency). The planned canonical
`IdempotencyKey` value object and its canonical expiry behavior remain task 5.6;
task 5.3 retains the opaque non-null/nonblank string while its Redis cache uses
the approved infrastructure-level 24-hour TTL.

**Bounded contexts (one per service):** SaleContext, InventoryContext (does not know what a `FlashSale` is), OrderContext (uses `PurchaseIntent`, not "Reservation," via an Anti-Corruption Layer), NotificationContext (Conformist), AnalyticsContext (Conformist).

**The only planned ACL in the system** is InventoryContext → OrderContext:
`Reservation`→`PurchaseIntent`, `ReservationId`→`PurchaseIntentId`,
`StockReserved`→`PurchaseConfirmation`, status terms
`PENDING/CONFIRMED/EXPIRED`→`OPEN/CONSUMED/LAPSED`. `PurchaseIntentId` now exists
inside OrderContext; `InventoryEventTranslator` and all cross-service behavior
remain planned.

**Domain event envelope:** `eventId` (UUID v4, dedup key), `eventType`, `eventVersion` (minor = backward-compatible, breaking = new type), `occurredAt`, `aggregateId`, `aggregateType`, `payload`. Consumers must ignore unknown fields.

**Domain event catalogue:**
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

## Infrastructure

### Repository structure
```
FlashSalePlatform/                         VERIFIED — make up runs from this directory
├── Makefile                               VERIFIED — moved from deployment/docker/ to root
├── deployment/
│   └── docker/                            VERIFIED — confirmed from IDE screenshot
│       ├── docker-compose.yml             VERIFIED — file exists and is parsed by Docker
│       ├── .env                           VERIFIED — created by cp .env.example .env
│       ├── .env.example                   VERIFIED
│       ├── config/redis-node.conf         VERIFIED — fixed (inline comments removed)
│       ├── init-scripts/                  VERIFIED — clickhouse/, inventory-db/, orders-db/, sales-db/ folders visible
│       └── scripts/health-check.sh        VERIFIED
├── docs/                                  VERIFIED (folder)
│   ├── architecture/                      PLANNED — Final-Spec-Council.md, DomainModel.md, DatabaseSchema.md,
│   │                                                 KafkaDesign.md, RedisDesign.md, PRD-FlashSalePlatform.md,
│   │                                                 Build-Plan.md, schema.sql — generated, not confirmed placed
│   └── adr/01-Decisions.md                PLANNED — generated, not confirmed placed
├── services/                              PLANNED — directory not yet created
├── testing/                               PLANNED — directory not yet created
├── benchmarks/                            PLANNED — directory not yet created
├── incidents/                             PLANNED — directory not yet created
├── README.md                              PLANNED — generated, not confirmed placed
├── INFRASTRUCTURE.md                      PLANNED — generated, not confirmed placed
├── build.gradle                           PLANNED — not written
├── settings.gradle                        PLANNED — not written
└── .gitignore                             PLANNED — generated, not confirmed placed
```

### Prerequisites (PLANNED — documented)
Docker Engine 24.0+, Docker Compose v2 (plugin, not the hyphenated v1 CLI), GNU Make 3.81+, redis-cli 7.x, psql 16.x, curl.

### PostgreSQL
| Instance | Image | Host port | Status |
|---|---|---|---|
| flash-sale-sales-db | postgres:16.3-alpine | 5432 | VERIFIED healthy — `pg_isready` returned "accepting connections" |
| flash-sale-inventory-db | postgres:16.3-alpine | 5433 | VERIFIED healthy |
| flash-sale-orders-db | postgres:16.3-alpine | 5434 | VERIFIED healthy |

User `flashsale`, password `flashsale_dev` (dev-only, intentionally weak — PLANNED convention). `lock_timeout` on `inventory-db`: PLANNED — set in `docker-compose.yml` command block, never verified with `SHOW lock_timeout`. Flyway migrations: PLANNED — init scripts create extensions only; no application tables exist yet.

### ClickHouse
| Container | Image | HTTP port | Native TCP port | Status |
|---|---|---|---|---|
| flash-sale-clickhouse | clickhouse/clickhouse-server:24.3.3-alpine | 8123 | 19000 (remapped from 9000) | VERIFIED healthy |

HTTP interface: VERIFIED — `make health` returned `Ok.` on ping. Port 9000 → 19000 remap: VERIFIED — `sed -i '' 's/"9000:9000"/"19000:9000"/'` applied and confirmed with grep; host port 9000 was already in use. `sale_events` table: PLANNED — init SQL was written; table existence never confirmed with a query.

### Tooling UIs
| Container | Image | URL | Status |
|---|---|---|---|
| flash-sale-kafka-ui | provectuslabs/kafka-ui:v0.7.2 | http://localhost:18080 | VERIFIED — `make health` returned `✓ Kafka UI` |
| flash-sale-redisinsight | redislabs/redisinsight:2.50 | http://localhost:18081 | VERIFIED — container `Started` in docker compose output |

### Makefile
Location: `FlashSalePlatform/Makefile` — VERIFIED (moved from `deployment/docker/` to project root; confirmed because `make up` executes from project root without a `-f` flag).

---

## APIs

**Status: PLANNED — 0 of 7 endpoints implemented. No Java code exists in the repository.**

| Endpoint | Owner | Notes |
|---|---|---|
| `POST /api/v1/sales` | SaleService | Create sale; required: `productId`, `totalStock`, `saleStart`, `saleEnd`, `name` |
| `GET /api/v1/sales/{id}` | SaleService | Cached in Redis |
| `PATCH /api/v1/sales/{id}/status` | SaleService | Admin force-transition |
| `GET /api/v1/sales/{id}/active` | SaleService | Hot path — must be served from Redis; never touches Postgres while sale is `ACTIVE` |
| `GET /api/v1/sales/{id}/history` | SaleService | Immutable audit trail |
| `POST /api/v1/reservations` | InventoryService | Requires `Idempotency-Key`; returns `201`/`409 SOLD_OUT`/`409 SALE_NOT_ACTIVE`/`429` |
| `POST /api/v1/orders` | OrderService | Requires `Idempotency-Key` (`400` if missing); returns `202 Accepted` immediately |

Week 5 Slices 1–3 intentionally do not implement
`POST /api/v1/orders`; smoke verification returned HTTP 404.

All mutating endpoints require and propagate a `traceId` (UUID v4) via `X-Trace-Id`. All endpoints require a valid bearer token validated at the API gateway; services trust the gateway-propagated `userId` header and do not re-validate tokens.

---

## Redis

### Cluster topology
Design: 3 primary shards, 1 replica per shard, 6 nodes total, bootstrapped by a 7th one-shot init container. Hash-slot sharding `CRC16(key) % 16384`; hash tags (e.g. `{saleId}`) co-locate related keys on one shard.

| Node | Image | Host port | Container status |
|---|---|---|---|
| flash-sale-redis-1 | redis:7.2.5-alpine | 7001 | VERIFIED healthy |
| flash-sale-redis-2 | redis:7.2.5-alpine | 7002 | VERIFIED healthy |
| flash-sale-redis-3 | redis:7.2.5-alpine | 7003 | VERIFIED healthy |
| flash-sale-redis-4 | redis:7.2.5-alpine | 7004 | VERIFIED healthy |
| flash-sale-redis-5 | redis:7.2.5-alpine | 7005 | VERIFIED healthy |
| flash-sale-redis-6 | redis:7.2.5-alpine | 7006 | VERIFIED healthy |

**Cluster state:** VERIFIED — `cluster_state:ok`, `cluster_slots_assigned:16384`, `cluster_known_nodes:6`, `cluster_size:3` confirmed via `make health` and `make redis-cluster-info` on 2026-06-17. The `redis-cluster-init` command block was rewritten from YAML folded scalar to list form `[sh, -c, script]`; `sh: -a: not found` errors resolved; idempotency guard confirmed operational on second `make up`.

Eviction: `allkeys-lru`. Persistence: AOF `appendfsync everysec`. `cluster-node-timeout`: 5000ms. Keyspace notifications: `Ex` enabled.

### Key / layer design (ranked architecture sources)
| Layer | Owner | Key Pattern | Structure | TTL | Fallback |
|---|---|---|---|---|---|
| 1 — Stock counter | InventoryService | `stock:{saleId}` | String (int) | `saleEnd + 10 min` | `SELECT FOR UPDATE` in Postgres |
| 2 — Rate limiter | API Gateway / SaleService | `rate:{userId}:{window_minute}` | Sorted Set | 60s | Fail-open + audit log |
| 3 — Session & Idempotency | OrderService | `session:{userId}`, `idem:{userId}:{idempotencyKey}` | Hash / String | 5min / 24h | Permanent Postgres record |

Stock-counter Lua return codes: `-2` = cache miss, `-1` = sold out, `>= 0` = success.

A supplementary design document restructures this into 5 layers, uses a
different rate-limiter key pattern, and adds a Sale Metadata layer. The
user-scoped idempotency key is now approved. **See CONFLICTS.md.** Redis memory
cap is also documented inconsistently across sources (per-shard vs.
cluster-wide totals). **See CONFLICTS.md.**

### Lua scripts
| Script | Status |
|---|---|
| `stock_decrement.lua` | VERIFIED generated |
| `stock_prewarm.lua` | VERIFIED generated |
| `stock_release.lua` | VERIFIED generated |
| `stock_reconcile.lua` | VERIFIED generated |
| `rate_limit.lua` | VERIFIED generated |

Placement: PLANNED — `services/inventory-service/src/main/resources/lua/`, `services/sale-service/src/main/resources/lua/` (not created). Execution: PLANNED — no `StockCounterService` or `RateLimiterService` exists to call any of them.

### Fallback rule
Redis is never the source of truth. A Redis miss or unavailability falls back to `SELECT FOR UPDATE` in Postgres — PLANNED, untested (no code exists).

---

## Kafka

### Broker
| Container | Image | Status |
|---|---|---|
| flash-sale-kafka | apache/kafka:3.7.0 | VERIFIED — healthy 2026-06-17 |

Image migration: VERIFIED — `sed -i '' 's/bitnami\/kafka:3.7.0/apache\/kafka:3.7.0/'` applied and confirmed with grep. `KAFKA_CFG_` → `KAFKA_` migration: VERIFIED applied — `KAFKA_PROCESS_ROLES: broker,controller` confirmed present; `KAFKA_CFG_` returns nothing. Broker health: VERIFIED — `make health` returned `✓ Kafka broker reachable` on 2026-06-17 after the `KAFKA_*` env var fix.

### Topics (ranked architecture sources)
| Topic | Partitions | Partition Key | Retention | Status |
|---|---|---|---|---|
| `sale-events` | 8 | `saleId` | 7 days | PLANNED — not created |
| `inventory-events` | 16 | `productId` | 3 days | VERIFIED in InventoryService configuration and real Kafka Testcontainer tests; production RF=3/min ISR=2, single-broker test override RF=1/min ISR=1 |
| `order-events` | 8 | `saleId` | 3 days | PLANNED — not created |
| `notifications.dlq` | 4 | none | 14 days | PLANNED — not created |
| `analytics.dlq` | 4 | none | 14 days | PLANNED — not created; presence in the "final" architecture spec is disputed. **See CONFLICTS.md.** |

`AUTO_CREATE_TOPICS_ENABLE=false`; services own topic creation. Slice 5 adds only
InventoryService ownership of `inventory-events`; no other topic was added.

Supplementary design documents and the build plan additionally define `sale-events.retry`, `inventory-events.retry`, and `order-events.retry` topics, not present in the ranked architecture documents. **See CONFLICTS.md.**

### Consumer groups
`notification-svc-consumer` (all 3 topics), `analytics-svc-consumer` (all 3 topics) — PLANNED. OrderService's `inventory-events` consumer group name differs across documents. **See CONFLICTS.md.**

### Producer/consumer configuration
InventoryService producer configuration is VERIFIED: `acks=all`, three Kafka
client retries, 100 ms retry backoff, LZ4 compression, idempotence enabled,
maximum five in-flight requests per connection, 5 ms linger, 30 s request
timeout, and 120 s delivery timeout. Consumer configuration remains planned.

---

## Database

### Ownership
| Database | Owner Service | Port | Status |
|---|---|---|---|
| `sales_db` | SaleService | 5432 | VERIFIED running — no application tables |
| `inventory_db` | InventoryService | 5433 | VERIFIED running — no application tables |
| `orders_db` | OrderService | 5434 | VERIFIED — V1 `idempotency_keys`; V2 `orders` and `order_outbox` validated with PostgreSQL/Testcontainers |

Engine: PostgreSQL 16 (`postgres:16.3-alpine` VERIFIED). Zero cross-database foreign keys by design — cross-service references are opaque UUIDs.

### Schema status

**`sales_db`:** `flash_sales` (FlashSale aggregate root — status/total_stock/timestamps, `version` optimistic lock), `sale_schedules` (1:1 with `flash_sales`; `sale_end > sale_start`), `sale_status_history` (immutable, insert-only audit log).

**`inventory_db`:** `products` (global product definition, `sku` UNIQUE), `stock_levels` (Postgres source of truth for stock; `current_stock <= total_allocated`), `reservations` (partial unique index `(user_id, sale_id) WHERE status IN ('PENDING','CONFIRMED')` enforces one active reservation per user per sale; `idempotency_key` UNIQUE), `stock_reservation_log` (append-only audit), and infrastructure-owned `inventory_outbox` (V4; restrictive reservation FK, unique stable event ID, JSONB payload, publish/retry metadata).

**`orders_db`:** the implemented Task 5.4 `orders` schema uses
`(user_id, idempotency_key)` uniqueness and `reservation_id` uniqueness. Its
Order-owned `order_outbox` row has a unique stable `event_id`, stores
`occurred_at`, and starts unpublished in the same transaction as its order.
Polling, the unpublished partial index, and retry metadata are deferred to Week
6. Task 5.3 introduced only `idempotency_keys`, keyed
by `(user_id, idempotency_key)`, with an opaque response payload and validated
HTTP status. Its PostgreSQL record is permanent and initially has no
`order_id`; Redis is a best-effort user-scoped 24-hour cache. Flyway V1 and V2,
including atomic commit and rollback behavior, were verified against PostgreSQL
16.3 via Testcontainers.

**Key query patterns:** the VERIFIED Inventory outbox poller selects at most 100
unpublished rows ordered by `created_at` using `FOR UPDATE SKIP LOCKED`, retaining
the row locks through the Kafka batch attempt. The planned OrderService outbox
uses the same concurrency pattern. Stock fallback and expiry query descriptions
remain as documented for their respective slices.

**Task 5.4 schema verification:** `idempotency_keys`, `orders`, and
`order_outbox` were validated in Docker-backed PostgreSQL. All 10 Task 5.3/5.4
PostgreSQL/Testcontainers tests passed with no failures or skips.

---

## Runtime Verification

### Component status rollup
| Component | Status | Detail |
|---|---|---|
| PostgreSQL (×3) | VERIFIED healthy | `pg_isready` passed on all 3 instances |
| Redis node containers (×6) | VERIFIED healthy | `docker ps` showed `(healthy)` on all 6 |
| Redis cluster state | VERIFIED | `cluster_state:ok`, `cluster_known_nodes:6`, `cluster_size:3` — confirmed 2026-06-17 |
| Kafka broker | VERIFIED | `make health` returned `✓ Kafka broker reachable` — confirmed 2026-06-17 |
| ClickHouse | VERIFIED healthy | HTTP ping returned `Ok.` |
| Kafka UI | VERIFIED | `make health` returned `✓ Kafka UI` |
| RedisInsight | VERIFIED (partial) | Container `Started`; no explicit health check confirmed |
| Application services (×5) | PLANNED | Zero Java code exists |
| Kafka topics (×8) | PLANNED | None created |
| Lua scripts (×5) | VERIFIED generated | Not placed in any service, not executed |
| Flyway migrations | PLANNED | Extensions only; no application tables |
| Kubernetes / Helm | PLANNED | No charts, no cluster, no manifests |
| Tests (unit/integration/chaos/load) | PLANNED | No test code written |

### Docker commands
| Command | Status | Evidence |
|---|---|---|
| `make up` | VERIFIED working | Containers start from `FlashSalePlatform/` root |
| `make clean` | VERIFIED working | All 27 containers and volumes removed successfully |
| `make clean && make up` | VERIFIED working | Clean cycle confirmed in terminal output |
| `make health` | VERIFIED working — partial | Postgres and ClickHouse pass; Redis cluster and Kafka status unconfirmed post-fix |
| `make down` | VERIFIED — untested in session | Command exists in Makefile; not explicitly run |
| `make kafka-topics` | VERIFIED executable — returns empty list (no topics created; no services exist) | Kafka broker healthy |
| `make kafka-lag` | VERIFIED executable — returns empty (no consumer groups; no services exist) | Kafka broker healthy |
| `make redis-cluster-info` | VERIFIED — returned `cluster_state:ok`, `cluster_slots_assigned:16384`, `cluster_known_nodes:6`, `cluster_size:3` on 2026-06-17 |
| `make db-sales` / `make db-inventory` / `make db-orders` | PLANNED — untested | Postgres running but command never explicitly tested |

### Git / version control
Branch: `main`/`master` — VERIFIED, one branch, all work committed there. Branch strategy: PLANNED — no branching strategy, PR workflow, or branch-protection rules exist yet.

### Overall assessment
Week 1 (infrastructure foundation) is partially verified: Postgres, ClickHouse, and the tooling UIs are confirmed healthy; Redis cluster formation and Kafka broker health are not yet confirmed after applied fixes. Zero application code exists for any of the 5 services.

---

## Current Build Plan

**10-week roadmap (PLANNED — 38 total tasks, 0 blocked weeks):**

| Week | Phase | Goal |
|---|---|---|
| 1 | Foundation | Full infra stack runnable locally; Flyway migrations auto-applied; seed data present |
| 2 | Core Services | SaleService skeleton; `FlashSale` aggregate with Java 21 sealed-interface state machine |
| 3 | Core Services | InventoryService stock counter; Lua decrement live; Postgres fallback tested |
| 4 | Core Services | `Reservation` aggregate live; partial unique index enforces one-active-reservation rule |
| 5 | Core Services | OrderService core + Transactional Outbox |
| 6 | Integration | End-to-end Kafka wiring: outbox poller + saga consumer |
| 7 | Integration | `GET /active` served from Redis; rate limiter enforced |
| 8 | Resilience | Retry/DLQ classification; NotificationService dispatch stubs |
| 9 | Observability | AnalyticsService batch-writes to ClickHouse; Prometheus metrics; traceId propagation |
| 10 | Production Readiness | Helm charts + HPA; Gatling load test at 50k users |

**Current status:** Week 5 is in progress. Build Plan tasks 5.1–5.4 are complete;
task 5.2 is committed at `df6d98ff1b9620a31a03cfdf0d0427aab922d964`,
and Task 5.3 is committed at
`f9a5e3b32b86c5418c9fb31ee7a31ed7ecad83e9`; Task 5.4 is committed at
`cb2081f`.

Task 5.4 verification passed 35 non-container OrderService tests and all 10
Task 5.3/5.4 PostgreSQL/Testcontainers tests (`10 passed, 0 failed, 0 skipped`).
The relevant Gradle build and `git diff --check` also passed.

**Immediate next task:** Week 5 / Build Plan task 5.5 — implement
`POST /api/v1/orders` without pulling in Task 5.6, Task 5.7, or Week 6 Kafka
publication/consumption behavior.

---

## Known Conflicts

See CONFLICTS.md.
