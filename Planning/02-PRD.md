# Product Requirements Document
## Flash Sale Platform
**Version:** 1.0 | **Status:** Approved for Engineering
**Date:** 2026-06-15
**Architecture Reference:** Final-Spec-Council.md v2.0
**Owner:** Staff Engineer Council

> **Constraint:** This PRD is derived from Final-Spec-Council.md. No architectural decisions are changed or overridden here. All service boundaries, data ownership, Kafka topology, and Redis contracts defined in the spec are binding.

---

## Table of Contents

1. [Product Overview](#1-product-overview)
2. [Personas & Actors](#2-personas--actors)
3. [User Stories](#3-user-stories)
4. [Functional Requirements](#4-functional-requirements)
5. [Non-Functional Requirements](#5-non-functional-requirements)
6. [Error Scenarios](#6-error-scenarios)
7. [Edge Cases](#7-edge-cases)
8. [Acceptance Criteria Summary](#8-acceptance-criteria-summary)

---

## 1. Product Overview

### 1.1 Problem Statement

Time-limited flash sales collapse demand into a narrow window, creating a thundering herd problem that kills most e-commerce backends. Inventory correctness fails under concurrency (overselling), user experience degrades under load (timeouts, duplicate orders), and operational visibility disappears exactly when it is needed most.

### 1.2 Product Goal

Build a backend platform that runs flash sales at scale — correct inventory, fair allocation, idempotent ordering, and observable operations — without sacrificing latency under peak load.

### 1.3 Scope

**In scope for v1.0:**
- Flash sale creation, scheduling, and lifecycle management
- Concurrent inventory reservation with atomic stock control
- Idempotent order creation with saga-based consistency
- Async notifications (email, push, SMS) on key events
- Real-time analytics event ingestion

**Out of scope for v1.0:**
- Payment processing (stubbed — PaymentService is a future bounded context)
- User authentication and identity (assumed provided by upstream API gateway)
- Product catalog management (products are pre-loaded; no catalog API)
- Customer-facing storefront (this is a backend platform; UI is out of scope)

### 1.4 Services Involved

| Service | Role |
|---|---|
| SaleService | Owns flash sale lifecycle and state machine |
| InventoryService | Owns stock levels, reservations, atomic decrement |
| OrderService | Owns order lifecycle, idempotency, saga orchestration |
| NotificationService | Async fan-out to email/push/SMS — stateless |
| AnalyticsService | Kafka consumer writing to ClickHouse |

---

## 2. Personas & Actors

### 2.1 Buyer
An authenticated end user who participates in flash sales. Has one active session. Expects immediate feedback on reservation success or failure. Will retry on timeout.

**Goals:** Reserve a product before it sells out. Receive confirmation. Not be double-charged on retry.

**Frustrations:** "I clicked Buy but don't know if it went through." Duplicate orders. Selling out before their request was processed despite clicking at the right time.

### 2.2 Sale Administrator
An internal operator who creates, schedules, and monitors flash sales. Needs to set stock levels, configure sale windows, force-end a sale if something goes wrong.

**Goals:** Configure a sale accurately. Monitor stock depletion in real time. Abort a sale cleanly if needed.

**Frustrations:** No visibility into real-time stock levels. Inability to stop a sale mid-flight. Audit trail gaps.

### 2.3 Platform Operator (SRE / On-Call)
An engineer responsible for platform health during a sale event. Needs dashboards, alerts, and the ability to diagnose incidents without downtime.

**Goals:** Know what is happening right now. Get paged before the system degrades. Diagnose and mitigate within minutes.

**Frustrations:** Metrics that lag. Ambiguous alert thresholds. Kafka consumer lag with no context.

### 2.4 System Actor — Scheduler
An internal time-based trigger (e.g., Spring Scheduler in SaleService) that transitions sale status from `SCHEDULED` to `ACTIVE` at `sale_start` and from `ACTIVE` to `ENDED` at `sale_end` or when stock reaches zero.

---

## 3. User Stories

### 3.1 Sale Administrator Stories

**US-001 — Create a Flash Sale**
> As a Sale Administrator, I want to create a flash sale with a product, stock quantity, start time, and end time, so that the platform is ready to accept reservations at the configured time.

Acceptance criteria:
- Sale is created in `SCHEDULED` status.
- `total_stock` must be > 0.
- `sale_start` must be in the future at creation time.
- `sale_end` must be > `sale_start`.
- Response includes the generated `saleId`.
- Sale metadata is readable via `GET /api/v1/sales/{id}` immediately after creation.

---

**US-002 — Monitor Sale Progress**
> As a Sale Administrator, I want to see the current stock level and reservation count for an active sale in near-real time, so that I can anticipate sell-out and communicate to operations.

Acceptance criteria:
- Analytics dashboard reflects reservation events within 5 seconds of occurrence.
- Stock counter is queryable via `GET /api/v1/sales/{id}` (served from Redis cache during active sale).
- Dashboard shows total reservations, confirmed orders, and failed reservations.

---

**US-003 — Force-End a Sale**
> As a Sale Administrator, I want to force-end an active sale early, so that I can respond to inventory errors, fraud signals, or operational incidents.

Acceptance criteria:
- `PATCH /api/v1/sales/{id}/status` with `{ "status": "ENDED" }` transitions the sale immediately.
- A `SaleEnded` event is published to `sale-events` within 1 second of the status change.
- No new reservations are accepted after the transition.
- In-flight reservations that arrived before the transition are honoured if stock permits.
- Existing confirmed orders are not affected.

---

**US-004 — View Sale Audit Trail**
> As a Sale Administrator, I want to see a log of all status transitions for a sale, so that I have a complete audit trail for compliance and incident review.

Acceptance criteria:
- Every status transition is recorded with timestamp and actor (scheduler vs. admin).
- Audit log is queryable via `GET /api/v1/sales/{id}/history`.
- Log entries are immutable.

---

### 3.2 Buyer Stories

**US-005 — Reserve a Product**
> As a Buyer, I want to reserve a product during an active flash sale, so that I can proceed to purchase before stock runs out.

Acceptance criteria:
- `POST /api/v1/reservations` with `saleId`, `userId`, `quantity`, `Idempotency-Key` header.
- Returns `201 Created` with `reservationId` and `expiresAt` on success.
- Returns `409 Conflict` with reason `SOLD_OUT` when stock is zero.
- Returns `409 Conflict` with reason `SALE_NOT_ACTIVE` when sale is not in `ACTIVE` status.
- Returns `429 Too Many Requests` if rate limit exceeded (10 requests/user/minute).
- Reservation expires after configured TTL (default 10 minutes) if not converted to an order.
- P99 latency ≤ 50ms under load.

---

**US-006 — Place an Order**
> As a Buyer, I want to convert my reservation into a confirmed order, so that my purchase is recorded and I receive confirmation.

Acceptance criteria:
- `POST /api/v1/orders` with `reservationId`, `userId`, `Idempotency-Key` header.
- Returns `202 Accepted` immediately; order is processed asynchronously.
- Idempotent: retrying with the same `Idempotency-Key` returns the same response without creating a duplicate order.
- Buyer receives a notification (email and/or push) when order status transitions to `CONFIRMED`.
- Returns `422 Unprocessable Entity` if reservation is expired or already consumed.

---

**US-007 — Receive Confirmation Notification**
> As a Buyer, I want to receive a notification when my order is confirmed, so that I have a record of my purchase without polling the API.

Acceptance criteria:
- Notification is dispatched within 30 seconds of `OrderCreated` event publication.
- Notification contains: order ID, product name, sale name, amount, and timestamp.
- Notification is sent via at least one channel (email or push) based on buyer preferences.
- Duplicate notifications are not sent for the same order ID (deduplicated at NotificationService level).

---

**US-008 — Retry a Failed Request Safely**
> As a Buyer (or client application), I want my retry of a timed-out reservation or order request to be safe, so that I am not double-charged or assigned double stock.

Acceptance criteria:
- Retrying `POST /api/v1/reservations` with the same `Idempotency-Key` returns the original response.
- Retrying `POST /api/v1/orders` with the same `Idempotency-Key` returns the original response.
- No duplicate reservation or order record is created.
- Idempotency keys are honoured for 24 hours from first use.

---

### 3.3 Platform Operator Stories

**US-009 — Observe System Health During a Sale**
> As a Platform Operator, I want real-time metrics and alerts during a flash sale, so that I can detect and respond to degradation before buyers are impacted.

Acceptance criteria:
- Reservation P99 latency exposed as a metric, alerting if it exceeds 100ms for 60 seconds.
- Kafka consumer lag exposed per consumer group, alerting if lag exceeds 10,000 messages.
- Redis memory usage exposed, alerting if utilisation exceeds 80% of cap.
- `notifications.dlq` depth exposed, alerting if it exceeds 100 messages.
- All services expose `/actuator/health/liveness` and `/actuator/health/readiness`.

---

**US-010 — Diagnose a Failed Reservation**
> As a Platform Operator, I want to trace a specific reservation request end-to-end, so that I can determine exactly where and why it failed.

Acceptance criteria:
- Every request carries a `traceId` propagated across all services.
- Trace spans are emitted to Tempo (or compatible backend).
- A failed reservation's trace shows: gateway auth, Redis Lua return code, Postgres fallback (if triggered), Kafka publish result.
- Log entries include `traceId`, `saleId`, `userId`, `reservationId` where applicable.

---

## 4. Functional Requirements

### 4.1 SaleService

**FR-001** The system shall expose `POST /api/v1/sales` to create a flash sale. Required fields: `productId`, `totalStock`, `saleStart` (ISO-8601), `saleEnd` (ISO-8601), `name`.

**FR-002** The system shall enforce the sale state machine: `SCHEDULED → ACTIVE → ENDED → ARCHIVED`. No state shall be skippable. No reverse transitions are permitted except `ACTIVE → ENDED` via admin force-end.

**FR-003** The Scheduler actor shall transition a sale from `SCHEDULED` to `ACTIVE` at `saleStart ± 5 seconds`. Clock skew tolerance is ± 5 seconds.

**FR-004** The Scheduler actor shall transition a sale from `ACTIVE` to `ENDED` when either `saleEnd` is reached or Redis stock counter reaches zero, whichever occurs first.

**FR-005** The system shall publish a `SaleStarted` event to `sale-events` on `SCHEDULED → ACTIVE` transition, and a `SaleEnded` event on `ACTIVE → ENDED` transition.

**FR-006** The system shall pre-warm the Redis stock counter for a sale 60 seconds before `saleStart`. Pre-warming sets `stock:{saleId} = totalStock` with TTL = `(saleEnd - now) + 10 minutes`.

**FR-007** `GET /api/v1/sales/{id}/active` shall be served exclusively from Redis during an active sale. It shall never query Postgres on the hot path while the sale is `ACTIVE`.

**FR-008** The system shall record every status transition with timestamp and actor identity in an immutable audit log queryable via `GET /api/v1/sales/{id}/history`.

---

### 4.2 InventoryService

**FR-009** The system shall execute stock reservation using an atomic Lua script in Redis. The script shall: check for cache miss (return `-2`), check for zero stock (return `-1`), decrement and return remaining stock (`>= 0`).

**FR-010** On a Redis cache miss (return code `-2`), the system shall fall back to `SELECT stock FROM products WHERE id = ? FOR UPDATE` in Postgres, decrement transactionally, and re-warm the Redis counter.

**FR-011** The system shall reject a reservation with `409 SOLD_OUT` when the Redis Lua script returns `-1`.

**FR-012** The system shall reject a reservation with `409 SALE_NOT_ACTIVE` when the sale status in Redis is not `ACTIVE` (checked before the Lua decrement).

**FR-013** The system shall publish a `StockReserved` event to `inventory-events` (partition key: `productId`) on every successful reservation. Event payload shall include: `eventId`, `eventVersion`, `saleId`, `userId`, `reservationId`, `quantity`, `remainingStock`, `occurredAt`.

**FR-014** The system shall publish a `StockReleased` event to `inventory-events` when a reservation expires without being converted to an order.

**FR-015** Reservations shall expire after a configurable TTL (default 10 minutes). Expiry shall be enforced via Redis key TTL on reservation metadata and a Postgres background sweep for durability.

---

### 4.3 OrderService

**FR-016** The system shall expose `POST /api/v1/orders` accepting `reservationId`, `userId`, and the `Idempotency-Key` header (required). Requests without `Idempotency-Key` shall receive `400 Bad Request`.

**FR-017** The system shall check for an existing idempotency key in Redis (`idem:{key}`) before processing. On hit, return the cached response immediately without re-processing.

**FR-018** On Redis miss, the system shall check the Postgres `idempotency_keys` table. On hit, return the stored response and re-warm the Redis cache.

**FR-019** Order creation shall write the `orders` record and the `order_outbox` event in a single DB transaction. Partial success (order written, outbox not written, or vice versa) is not permitted.

**FR-020** The outbox poller shall run on a configurable interval (default 500ms). It shall publish all unpublished outbox rows to Kafka and mark them `published = true` within the same operation.

**FR-021** The system shall publish an `OrderCreated` event to `order-events` (partition key: `saleId`) on successful order creation. Event payload shall include: `eventId`, `eventVersion`, `orderId`, `reservationId`, `userId`, `saleId`, `amount`, `occurredAt`.

**FR-022** The system shall consume `inventory-events` (consumer group: `order-svc-reservation-consumer`) to confirm that a reservation exists and is valid before creating an order. An order referencing an expired or non-existent reservation shall be rejected with `422 Unprocessable Entity`.

**FR-023** On `PaymentFailed` (future stub), the system shall publish `ReservationReleased` to trigger stock restoration in InventoryService.

---

### 4.4 NotificationService

**FR-024** The system shall consume `sale-events`, `inventory-events`, and `order-events` under consumer group `notification-svc-consumer`.

**FR-025** On `OrderCreated` event, the system shall dispatch a notification to the buyer via at least one configured channel (email, push, or SMS) within 30 seconds.

**FR-026** On `SaleStarted` event, the system shall dispatch sale-open notifications to buyers on the waitlist (if configured).

**FR-027** On `ReservationExpired` event, the system shall notify the buyer that their reservation has lapsed and invite them to retry.

**FR-028** The system shall publish failed notifications to `notifications.dlq` after exhausting retry attempts (3 attempts with exponential backoff: 1s, 4s, 16s).

**FR-029** The system shall deduplicate notifications by `orderId` or `reservationId` to prevent duplicate dispatch on Kafka redelivery.

---

### 4.5 AnalyticsService

**FR-030** The system shall consume all three topics (`sale-events`, `inventory-events`, `order-events`) under consumer group `analytics-svc-consumer`.

**FR-031** Every consumed event shall be written to the ClickHouse `sale_events` wide table within 5 seconds of publication.

**FR-032** The system shall batch writes to ClickHouse in micro-batches of up to 1,000 events or 1 second, whichever comes first.

**FR-033** The `sale_events` table shall include: `event_id`, `event_type`, `event_version`, `sale_id`, `user_id`, `occurred_at`, `raw_payload` (JSON), `ingested_at`.

---

### 4.6 Cross-Cutting Requirements

**FR-034** Every mutating endpoint shall require and propagate a `traceId` (UUID v4) in request and response headers (`X-Trace-Id`).

**FR-035** All services shall expose Spring Boot Actuator endpoints: `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/metrics`, `/actuator/prometheus`.

**FR-036** The API gateway shall enforce rate limiting: 10 requests per user per minute on reservation endpoints. Rate limiter state is stored in Redis Layer 2 (Sorted Set, sliding window).

**FR-037** The API gateway shall enforce authentication on all endpoints. Unauthenticated requests shall receive `401 Unauthorized` before reaching any service.

**FR-038** All Kafka event schemas shall include `eventVersion` field. Schema changes shall be backward-compatible. Breaking changes require a new event type.

---

## 5. Non-Functional Requirements

### 5.1 Performance

**NFR-001 — Reservation Latency**
`POST /api/v1/reservations` P99 latency shall be ≤ 50ms at peak load (50,000 concurrent users).
Mechanism: Redis Lua DECR; Postgres is never touched on the hot path when Redis is healthy.

**NFR-002 — Order Latency**
`POST /api/v1/orders` shall return `202 Accepted` within 100ms P99. Order processing is asynchronous; the `202` acknowledges receipt, not completion.

**NFR-003 — Sale Active Check**
`GET /api/v1/sales/{id}/active` shall respond within 10ms P99. Served exclusively from Redis. Must not degrade under thundering herd at sale start.

**NFR-004 — Analytics Lag**
Events shall appear in ClickHouse within 5 seconds of Kafka publication under normal load.

**NFR-005 — Notification Dispatch**
Notifications shall be dispatched within 30 seconds of the triggering Kafka event under normal load.

---

### 5.2 Scalability

**NFR-006 — Horizontal Scale**
Each service shall be horizontally scalable via Kubernetes HPA. No service shall hold in-process state that prevents multi-replica deployment.

**NFR-007 — Kafka Consumer Scaling**
Consumer group parallelism shall match topic partition count. Adding replicas to NotificationService or AnalyticsService shall proportionally increase throughput up to the partition limit.

**NFR-008 — Redis Cluster**
Redis shall run in Cluster mode with 3 primary shards. Stock counter keys shall be distributed across shards by `saleId`. No single shard shall become a bottleneck for a single sale.

**NFR-009 — Kubernetes Resource Baselines**

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit | Min Replicas | Max Replicas |
|---|---|---|---|---|---|---|
| SaleService | 250m | 1000m | 512Mi | 1Gi | 2 | 8 |
| InventoryService | 250m | 1000m | 512Mi | 1Gi | 2 | 10 |
| OrderService | 250m | 1000m | 512Mi | 1Gi | 2 | 10 |
| NotificationService | 100m | 500m | 256Mi | 512Mi | 1 | 5 |
| AnalyticsService | 100m | 500m | 256Mi | 512Mi | 1 | 5 |

HPA trigger: CPU utilisation > 70%.

---

### 5.3 Availability

**NFR-010 — Service Availability Target**
InventoryService and OrderService: 99.9% monthly uptime (≤ 43 minutes downtime/month).
SaleService: 99.9%.
NotificationService, AnalyticsService: 99.5% (non-transactional path).

**NFR-011 — Degraded Mode**
If Redis is unavailable, InventoryService shall fall back to Postgres `SELECT FOR UPDATE`. Throughput degrades; correctness does not.

**NFR-012 — NotificationService Isolation**
NotificationService downtime shall have zero impact on reservation, inventory, or order creation. These flows must complete successfully regardless of notification delivery status.

**NFR-013 — AnalyticsService Isolation**
AnalyticsService downtime shall have zero impact on any transactional flow. Event lag accumulates in Kafka; catch-up occurs on recovery.

**NFR-014 — Graceful Shutdown**
All services shall handle `SIGTERM` by: completing in-flight requests (up to 30 seconds), committing Kafka consumer offsets, flushing the outbox poller, and releasing database connections cleanly.

---

### 5.4 Consistency

**NFR-015 — Inventory Correctness**
Oversell rate shall be 0. Under no failure mode shall confirmed orders exceed `totalStock`.

**NFR-016 — Order Idempotency**
Duplicate order creation rate (same `Idempotency-Key` producing two order records) shall be 0.

**NFR-017 — Event Delivery**
All Kafka events shall be delivered at-least-once. Consumers shall be idempotent. Exactly-once delivery is not required; duplicate tolerance is.

**NFR-018 — Saga Compensation**
Every saga step that can fail shall have a defined compensating transaction. No saga shall be left in a permanently inconsistent state after compensation exhaustion. After 3 compensation retries, the failure is escalated to the dead-letter topic and ops team.

---

### 5.5 Security

**NFR-019 — Authentication**
All API endpoints shall require a valid bearer token. Token validation occurs at the API gateway. Services shall trust the gateway-propagated `userId` header; they shall not re-validate tokens.

**NFR-020 — Rate Limiting**
10 reservation requests per user per minute enforced at the gateway via Redis sliding window. Violation returns `429 Too Many Requests` with `Retry-After` header.

**NFR-021 — Input Validation**
All service endpoints shall validate request payloads and reject malformed input with `400 Bad Request` and a structured error body. No unvalidated input shall reach business logic or database queries.

**NFR-022 — Secret Management**
Database passwords, Redis auth tokens, and Kafka credentials shall be stored in Kubernetes Secrets. They shall not appear in environment variables, config files committed to source control, or application logs.

**NFR-023 — Audit Logging**
All admin-initiated state transitions (sale creation, force-end, status override) shall be logged with actor identity, timestamp, and before/after state. Audit logs are append-only.

---

### 5.6 Observability

**NFR-024 — Metrics**
All services shall expose Prometheus-compatible metrics including: request rate, error rate, P50/P95/P99 latency per endpoint, JVM heap, GC pause duration, Kafka consumer lag per group, Redis command latency.

**NFR-025 — Distributed Tracing**
All services shall emit OpenTelemetry spans. Trace context (`traceId`, `spanId`) shall be propagated across HTTP calls and Kafka message headers. Sampling rate: 100% on error, 10% on success.

**NFR-026 — Structured Logging**
All log output shall be structured JSON. Required fields: `timestamp`, `level`, `service`, `traceId`, `saleId` (where applicable), `userId` (where applicable), `message`.

**NFR-027 — Alerting Thresholds**

| Signal | Warning | Critical |
|---|---|---|
| Reservation P99 latency | > 100ms for 60s | > 250ms for 30s |
| Kafka consumer lag | > 5,000 messages | > 10,000 messages |
| Redis memory utilisation | > 70% of cap | > 85% of cap |
| `notifications.dlq` depth | > 50 messages | > 100 messages |
| Error rate (5xx) per service | > 1% for 60s | > 5% for 30s |

---

### 5.7 Testability

**NFR-028 — Unit Tests**
Each service shall have unit tests covering: Lua script return code handling, idempotency key lookup paths, saga event routing, state machine transitions. Coverage target: ≥ 80% line coverage on business logic packages.

**NFR-029 — Integration Tests**
Integration tests shall use Testcontainers to spin up real Postgres, Redis, and Kafka instances. Tests shall cover: end-to-end reservation flow, oversell prevention under concurrent load, outbox publication, saga compensation on payment failure.

**NFR-030 — Load Tests**
A Gatling or k6 load test suite shall simulate 50,000 concurrent reservation requests against a single sale. Pass criteria: P99 ≤ 50ms, zero oversells, zero duplicate orders.

**NFR-031 — Property-Based Tests**
The Redis Lua stock decrement script shall be tested with property-based tests (jqwik or similar) verifying: stock never goes negative, return codes are correct across all boundary conditions, concurrent execution produces deterministic results.

---

## 6. Error Scenarios

### 6.1 Inventory Errors

**ERR-001 — Stock Exhausted Mid-Sale**
Trigger: Last unit reserved; subsequent requests arrive.
Expected behaviour:
- Redis Lua returns `-1`. InventoryService returns `409 { "error": "SOLD_OUT", "saleId": "..." }`.
- SaleService Scheduler detects stock = 0 and transitions sale to `ENDED`.
- `SaleEnded` event published to `sale-events`.
- All subsequent reservation requests return `409 SALE_NOT_ACTIVE`.
- No race condition: the last reservation and the `ENDED` transition are independent operations; the Lua script handles the boundary atomically.

**ERR-002 — Redis Unavailable at Reservation Time**
Trigger: Redis Cluster is unreachable (network partition, crash).
Expected behaviour:
- Circuit breaker opens after 5 consecutive Redis failures (500ms timeout).
- InventoryService falls back to `SELECT FOR UPDATE` on Postgres.
- Throughput degrades to Postgres write capacity (estimated 2,000–5,000 TPS vs Redis 100,000+ TPS).
- No oversell: Postgres row lock guarantees correctness.
- Alert fires: `Redis connectivity failure — degraded mode active`.
- No `503` returned to buyer unless Postgres also fails.

**ERR-003 — Redis Cache Miss at Sale Start (Cold Cache)**
Trigger: Sale transitions to `ACTIVE` but Redis pre-warm has not completed.
Expected behaviour:
- First request hits cache miss (return `-2`).
- InventoryService loads stock from Postgres, sets Redis counter, retries Lua script.
- Buyer sees slightly elevated latency on first request; subsequent requests are Redis-fast.
- Pre-warm at T-60s (FR-006) prevents this under normal conditions. This path handles pre-warm failure.

**ERR-004 — Reservation Expiry During Order Creation**
Trigger: Buyer's 10-minute reservation TTL expires between reservation and order placement.
Expected behaviour:
- OrderService consumes `inventory-events` to validate reservation. Expired reservation is not present.
- `POST /api/v1/orders` returns `422 { "error": "RESERVATION_EXPIRED", "reservationId": "..." }`.
- Stock has already been released via `StockReleased` event when the reservation expired.
- Buyer must re-attempt reservation if sale is still active.

---

### 6.2 Order Errors

**ERR-005 — Missing Idempotency Key**
Trigger: Client calls `POST /api/v1/orders` without `Idempotency-Key` header.
Expected behaviour:
- OrderService returns `400 { "error": "MISSING_IDEMPOTENCY_KEY", "message": "Idempotency-Key header is required." }`.
- Request is rejected before any business logic executes.

**ERR-006 — Duplicate Order Attempt (Same Key)**
Trigger: Client retries `POST /api/v1/orders` with the same `Idempotency-Key` within 24 hours.
Expected behaviour:
- Redis cache hit: original response returned within 10ms.
- No second order record created.
- Response is identical to the original (same `orderId`, same status).
- HTTP status code matches original response.

**ERR-007 — Outbox Poller Failure**
Trigger: Background outbox poller crashes or fails to publish to Kafka.
Expected behaviour:
- Order record exists in Postgres with `published = false` in outbox.
- Poller retries on next interval (default 500ms).
- If Kafka is unreachable for > 5 minutes, alert fires: `Outbox backlog > threshold`.
- No data loss: outbox rows persist until explicitly published and marked.
- Buyer has received `202 Accepted`; order will eventually be confirmed when Kafka recovers.

**ERR-008 — Saga Compensation — Payment Failure (Stub)**
Trigger: PaymentService (future) publishes `PaymentFailed` event.
Expected behaviour:
- OrderService consumes `PaymentFailed`, updates order status to `CANCELLED`.
- OrderService publishes `ReservationReleased` to `inventory-events`.
- InventoryService consumes `ReservationReleased`, increments Redis stock counter, updates Postgres.
- NotificationService dispatches `OrderCancelled` notification to buyer.
- Compensation is idempotent: replaying `PaymentFailed` produces the same outcome.

---

### 6.3 Infrastructure Errors

**ERR-009 — Kafka Broker Unavailable**
Trigger: Kafka cluster unreachable (broker failure, network partition).
Expected behaviour:
- Outbox poller accumulates unpublished rows in Postgres. No events are lost.
- Kafka consumers (NotificationService, AnalyticsService, OrderService) pause consumption. No processing occurs.
- On Kafka recovery, consumers resume from last committed offset. Events are processed in order.
- NotificationService and AnalyticsService lag catches up; transactional services resume normal operation.
- Alert fires: `Kafka producer failure — outbox backlog growing`.

**ERR-010 — Postgres Unavailable (InventoryService)**
Trigger: Postgres primary instance unreachable.
Expected behaviour:
- If Redis is healthy: reservation hot path continues unaffected (Redis-only path).
- Outbox poller pauses (cannot write).
- Stock re-warm on cache miss fails; cache miss returns `503` only if both Redis and Postgres are unavailable.
- Alert fires immediately: `Postgres connectivity failure`.
- On recovery: poller resumes; any missed Postgres writes (e.g., reservation records) are reconciled from Kafka event log.

**ERR-011 — Service Pod Crash During Request Processing**
Trigger: JVM crash or OOM kill mid-request.
Expected behaviour:
- For reservation: Redis Lua has either executed (stock decremented) or not. No partial state.
- For order creation: DB transaction either committed (outbox written) or rolled back. No partial state.
- Client receives a network error or timeout and retries with the same `Idempotency-Key`.
- Retry is safe: idempotency layer absorbs the duplicate.

**ERR-012 — ClickHouse Unavailable (AnalyticsService)**
Trigger: ClickHouse instance unreachable.
Expected behaviour:
- AnalyticsService pauses writes. Kafka consumer lag increases.
- Transactional path (reservations, orders) is completely unaffected.
- On recovery, AnalyticsService resumes from last committed Kafka offset. No events are lost.
- Alert fires: `AnalyticsService ClickHouse write failure — consumer lag growing`.

---

### 6.4 Client Errors

**ERR-013 — Request to Inactive Sale**
Trigger: Buyer sends reservation request for a sale in `SCHEDULED`, `ENDED`, or `ARCHIVED` status.
Expected behaviour:
- SaleService `/active` check (Redis) returns false.
- InventoryService returns `409 { "error": "SALE_NOT_ACTIVE", "saleId": "...", "status": "ENDED" }`.
- No stock decrement attempted.

**ERR-014 — Rate Limit Exceeded**
Trigger: Buyer sends > 10 reservation requests within a 60-second window.
Expected behaviour:
- API gateway returns `429 Too Many Requests` with `Retry-After: 60` header.
- Request does not reach InventoryService.
- Rate limit state in Redis is not affected by the rejection (ZCARD checked, not incremented on rejection).

---

## 7. Edge Cases

### 7.1 Inventory Edge Cases

**EC-001 — Exactly Zero Stock Remaining After Last Reservation**
Scenario: 1 unit remaining. Two requests arrive simultaneously.
Expected: Lua script processes them sequentially (Redis single-threaded execution). First request gets the last unit (returns `0`). Second request gets `-1` (sold out). Zero oversell guaranteed. SaleService detects stock = 0 and transitions to `ENDED`.

**EC-002 — Sale Start Time in the Past at Creation**
Scenario: Administrator creates a sale with `saleStart` = 1 minute ago (clock drift or misconfiguration).
Expected: `POST /api/v1/sales` returns `400 { "error": "INVALID_SALE_START", "message": "saleStart must be in the future." }`. Sale is not created.

**EC-003 — totalStock = 0 at Creation**
Scenario: Administrator attempts to create a sale with `totalStock = 0`.
Expected: `400 { "error": "INVALID_STOCK", "message": "totalStock must be greater than zero." }`.

**EC-004 — Sale End Time Before Start Time**
Scenario: `saleEnd < saleStart` in creation request.
Expected: `400 { "error": "INVALID_SALE_WINDOW" }`.

**EC-005 — Stock Counter TTL Expires Before Sale Ends**
Scenario: Redis key TTL is miscalculated; counter expires while sale is still `ACTIVE`.
Expected: Cache miss triggers Postgres fallback (ERR-003 path). Stock is re-loaded and re-warmed. Sale continues. Root cause: TTL must be set to `(saleEnd - now) + 10 minutes`. FR-006 pre-warm enforces this; this is the recovery path if TTL is incorrectly set.

**EC-006 — Pre-Warm Occurs for a Cancelled Sale**
Scenario: Pre-warm runs at T-60s, but admin force-ends the sale between T-60s and T-0.
Expected: Redis key is set by pre-warm, then overwritten to `0` (or deleted) by the force-end. `SaleEnded` event published. Subsequent reservation attempts return `409 SALE_NOT_ACTIVE`. No buyer is able to reserve on the ended sale regardless of Redis state.

---

### 7.2 Order Edge Cases

**EC-007 — Idempotency Key Collision (Different Users, Same Key)**
Scenario: Two users independently generate the same `Idempotency-Key` UUID (astronomically unlikely but must be handled).
Expected: Idempotency key lookup is scoped by `userId` in addition to the key value. Key schema: `idem:{userId}:{idempotencyKey}`. Collision between users is impossible with this scoping.

**EC-008 — Idempotency Key TTL Expires; Buyer Retries**
Scenario: Buyer retries an order 25 hours after the first attempt. Redis key has expired. Postgres record exists.
Expected: Redis miss → Postgres hit → response returned from Postgres → Redis re-warmed. Buyer receives the original response. No duplicate order created.

**EC-009 — Outbox Row Published Twice (Duplicate Kafka Event)**
Scenario: Outbox poller publishes an event and marks it `published = true`, but crashes before committing. On restart, it publishes again.
Expected: Kafka consumers are idempotent (deduplicate by `eventId`). Duplicate `OrderCreated` events produce no duplicate order records, no duplicate notifications. `eventId` is the deduplication key at all consumers.

**EC-010 — Order Created for Already-Consumed Reservation**
Scenario: A reservation is consumed by one order. A second request (different `Idempotency-Key`) references the same `reservationId`.
Expected: OrderService finds the `reservationId` in the `orders` table with status `CONSUMED`. Returns `422 { "error": "RESERVATION_ALREADY_CONSUMED", "orderId": "<original>" }`.

**EC-011 — Saga Left Incomplete (Compensation Exhausted)**
Scenario: `PaymentFailed` event is published. OrderService attempts to publish `ReservationReleased`, but Kafka is down. Retries exhausted after 3 attempts.
Expected: Failure event written to dead-letter topic. Ops team paged. Manual intervention protocol: stock is audited from Postgres reservation table and reconciled. Stock recovery SLA: < 30 minutes with manual intervention.

---

### 7.3 Infrastructure Edge Cases

**EC-012 — Redis Cluster Resharding During Active Sale**
Scenario: Redis Cluster slot rebalancing occurs while a sale is active (maintenance window misconfiguration).
Expected: Stock counter key may be temporarily unavailable during slot migration. Redis client retries with `MOVED` / `ASK` redirects. If migration takes > 500ms, circuit breaker triggers Postgres fallback. No oversell occurs. Post-migration: Redis is re-warmed from Postgres.

**EC-013 — Clock Skew Between SaleService Pods**
Scenario: Two SaleService pods have clock drift > 5 seconds. One pod transitions sale to `ACTIVE`; the other still sees it as `SCHEDULED`.
Expected: Sale status is authoritative in Redis (written by the first pod to fire the transition). All pods read from Redis for the hot path. Clock skew affects the trigger time, not the consensus state. FR-003 clock skew tolerance (± 5 seconds) is enforced.

**EC-014 — Kafka Partition Leader Election During Sale**
Scenario: Kafka broker hosting `inventory-events` partition leader fails mid-sale. New leader election takes 10–30 seconds.
Expected: Producers receive `LEADER_NOT_AVAILABLE` and retry. Consumer groups pause and rebalance. No messages are lost (replication factor ≥ 3). Reservation requests that fail to produce a Kafka event are retried by the outbox poller. End-to-end latency spikes during election; correctness is preserved.

**EC-015 — HPA Scale-Up Lag at Sale Start**
Scenario: Sale starts with 3 InventoryService replicas. Thundering herd triggers CPU > 70%. HPA scale-up takes 60–90 seconds (Kubernetes default).
Expected: During scale-up lag, existing replicas absorb load via virtual thread concurrency. Response times increase. P99 may temporarily exceed the 50ms target. No correctness impact. Mitigation: pre-scale InventoryService to max replicas 5 minutes before `saleStart` via a pre-sale runbook. This is an operational procedure, not a code change.

**EC-016 — AnalyticsService Consumer Group Rebalance**
Scenario: AnalyticsService pod is killed during a sale. Kafka triggers consumer group rebalance.
Expected: Rebalance completes within 30 seconds (Kafka default session timeout). Events accumulate in Kafka during rebalance. On recovery, AnalyticsService resumes from last committed offset — events are not lost, but analytics lag temporarily exceeds the 5-second target. Transactional path unaffected.

---

## 8. Acceptance Criteria Summary

| ID | Requirement | Measurable Target | Test Type |
|---|---|---|---|
| AC-001 | Reservation P99 latency | ≤ 50ms at 50k concurrent users | Load test (k6/Gatling) |
| AC-002 | Oversell rate | 0 under all conditions | Concurrent integration test + property test |
| AC-003 | Order idempotency | 0 duplicate orders on retry | Integration test |
| AC-004 | Event delivery | At-least-once; 0 lost events on broker recovery | Chaos test (kill broker mid-sale) |
| AC-005 | Sale start accuracy | Transition within ± 5 seconds of `saleStart` | Integration test with time injection |
| AC-006 | Notification latency | ≤ 30 seconds post-`OrderCreated` | Integration test |
| AC-007 | Analytics lag | ≤ 5 seconds under normal load | Integration test |
| AC-008 | Redis fallback correctness | Zero oversell when Redis is unavailable | Chaos test (kill Redis mid-sale) |
| AC-009 | Saga compensation | Stock restored after `PaymentFailed` within 30 seconds | Integration test |
| AC-010 | Rate limiting | 429 on > 10 req/min per user; no reservation leaks | Integration test |
| AC-011 | Graceful shutdown | Zero in-flight request loss on `SIGTERM` | Rolling deploy test |
| AC-012 | Pre-warm reliability | Stock counter live in Redis 60s before sale start | Integration test |

---

*PRD derived from Final-Spec-Council.md v2.0. Architecture is binding. All functional and non-functional requirements are traceable to a specific service, ADR, or council decision. No requirements in this document contradict or extend the architecture specification.*

*Reviewers: Jordan Wei (Google), Priya Nair (Uber), Marcus Shaw (Amazon), Elena Kovac (Atlassian)*
*Next: Service skeleton generation, integration test harness, Lua script with property-based tests.*