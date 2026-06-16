# Final-Spec-Council.md
## Flash Sale Platform — Definitive Architecture Specification
**Status:** FINAL | **Version:** 2.0 | **Date:** 2026-06-15
**Supersedes:** Architecture-v1.md (3-service model, 2026-06-14)

---

## 0. Contradiction Resolution

### What Happened

| Session | Proposed | Rationale Given |
|---|---|---|
| Architecture v1 (yesterday) | 3 services: InventoryService, OrderService, NotificationService | Avoid microservice sprawl; 3 domains cleanly separated |
| Council session (today) | 5 services: + SaleService, + AnalyticsService | Domain separation, independent scaling, team ownership |

### Why the Contradiction Exists

The v1 design embedded **flash sale lifecycle management** inside `InventoryService` and treated **analytics** as a future concern. The council session correctly identified that both represent distinct bounded contexts with divergent scaling characteristics. However, the council session did not explicitly retire ADR-005 ("3 services is the right count") — it just added services without acknowledging the prior decision. That is an architectural governance failure.

### Council Verdict

**The 5-service model is the final architecture.**

Rationale:
1. `SaleService` is not a cosmetic split. Flash sale scheduling, status machine (SCHEDULED → ACTIVE → ENDED), and the countdown mechanics are a domain with its own write path, its own read-heavy load pattern (everyone polling "is the sale live yet?"), and its own failure mode. Keeping it in `InventoryService` violates Single Responsibility and creates a hotspot.
2. `AnalyticsService` is a pure consumer. It adds zero coupling to the transactional path. The only cost is a Kafka consumer group and a ClickHouse write path. Operational overhead is negligible; the interview value of demonstrating CQRS-at-scale is high.
3. The v1 principle that motivated 3 services — *"no sprawl without traffic justification"* — is preserved. Both new services have explicit justification below.

**Dissenting view on record (Atlassian / Elena Kovac):** `SaleService` and `InventoryService` could remain merged in teams below 20 engineers where cognitive load of 5 separate deploy targets outweighs the architectural purity. This spec targets a team of 4–8 engineers, so 5 services is justified.

---

## 1. Final Service Inventory

| Service | Owns | DB Schema | Kafka Role | Redis Role |
|---|---|---|---|---|
| `SaleService` | Sale lifecycle, scheduling, status machine | `sales_db` | Producer: `sale-events` | Cache: active sale metadata (TTL = sale duration) |
| `InventoryService` | Stock levels, reservation, atomic decrement | `inventory_db` | Producer: `inventory-events` | Layer 1: stock counter (Lua DECR) |
| `OrderService` | Order lifecycle, idempotency, saga orchestration | `orders_db` | Producer: `order-events`; Consumer: `inventory-events` | Layer 3: idempotency key cache (TTL 24h) |
| `NotificationService` | Email, push, SMS fan-out | None (stateless) | Consumer: all three topics | None |
| `AnalyticsService` | Event ingestion, metrics, dashboards | ClickHouse | Consumer: all three topics | None |

**Hard rules enforced across all 5 services:**
- Zero cross-service database joins. Cross-service data needs go through events or dedicated query APIs.
- No synchronous HTTP calls between services on the hot path (reserve → order flow). Kafka only.
- Each service is independently deployable with its own Dockerfile, Helm chart, and HPA config.

---

## 2. Service Boundaries — Detailed

### 2.1 SaleService

**Responsibility:** Owns the flash sale entity from creation to archival.

**Why it exists as a separate service:** The sale status (`SCHEDULED → ACTIVE → ENDED`) is read by every other service. Putting this in `InventoryService` means inventory logic must resolve "is the sale live?" before every stock check — a cross-domain concern leaking into the wrong service. `SaleService` exposes a lightweight read API that other services can call, and publishes `SaleStarted` / `SaleEnded` events that drive state transitions everywhere else.

**API surface:**
```
POST   /api/v1/sales              → create sale
GET    /api/v1/sales/{id}         → get sale (cached in Redis)
PATCH  /api/v1/sales/{id}/status  → admin: force status transition
GET    /api/v1/sales/{id}/active  → hot path: is sale live? (Redis-first)
```

**State machine:**
```
SCHEDULED ──(start time reached)──► ACTIVE ──(end time / stock = 0)──► ENDED
                                                                           │
                                                                    ARCHIVED (async)
```

**Scaling profile:** Read-heavy. Aggressive Redis caching of sale metadata. The `GET /active` endpoint must be served from Redis; it must never hit Postgres on the hot path.

---

### 2.2 InventoryService

**Responsibility:** Single source of truth for stock. Owns the atomic decrement.

**Why the Lua script is non-negotiable:**
```lua
-- Executed atomically in Redis. No race condition possible.
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -2 end   -- cache miss → fallback to Postgres
if stock <= 0   then return -1 end   -- sold out
redis.call('DECR', KEYS[1])
return stock - 1
```

Return codes: `-2` = miss (load from Postgres, retry), `-1` = sold out (return 409), `>= 0` = success.

**Fallback path (Redis down):** `SELECT FOR UPDATE` on the `stock` column in Postgres. This is the only safe fallback. A Redis miss must not return a guess.

**Kafka output:**
```json
{
  "eventType": "StockReserved",
  "eventVersion": "1.0",
  "eventId": "uuid",
  "occurredAt": "ISO-8601",
  "saleId": "uuid",
  "userId": "uuid",
  "reservationId": "uuid",
  "quantity": 1,
  "remainingStock": 142
}
```

---

### 2.3 OrderService

**Responsibility:** Idempotent order creation. Saga orchestration via Kafka choreography.

**Idempotency contract:**
```
POST /api/v1/orders
Idempotency-Key: <client-generated UUID v4>

1. Check Redis: key exists? → return cached 200/202 response. Done.
2. Check Postgres idempotency table: exists? → return stored result. Done.
3. Process order → write to DB + outbox in one transaction.
4. Store result in Redis (TTL 24h) and Postgres idempotency table.
5. Return result.
```

**Saga choreography (choreography over orchestration — 5 services do not warrant a central orchestrator):**
```
InventoryService  ──[StockReserved]──►  OrderService (consumes)
                                              │
                                        Creates order
                                              │
                                        [OrderCreated] ──►  NotificationService
                                                       ──►  AnalyticsService
                                              │
                                    Payment stub (future)
                                              │
                              [PaymentFailed] → publish [ReservationReleased]
                              [OrderTimeout]  → publish [ReservationExpired]
```

**Outbox pattern (non-negotiable):**

Write event to `order_outbox` in the same DB transaction as the order row. A background scheduler polls `WHERE published = false` and publishes to Kafka. This guarantees at-least-once delivery with no event loss on partial failure.

---

### 2.4 NotificationService

**Responsibility:** Async fan-out only. Stateless.

This service has no database. It consumes Kafka events and dispatches to email/push/SMS providers. It must never be on the synchronous request path. If it is down, the transactional flow is unaffected.

**Consumer group:** `notification-svc-consumer` subscribed to `sale-events`, `inventory-events`, `order-events`.

**Retry policy:** Exponential backoff on provider failures. Dead-letter topic: `notifications.dlq`. Operations team gets paged on DLQ depth > 100.

---

### 2.5 AnalyticsService

**Responsibility:** Event ingestion to ClickHouse for real-time dashboards and post-sale reporting.

**Why ClickHouse, not Postgres:** Flash sale analytics are columnar read workloads (aggregate queries across millions of events). ClickHouse handles 10M+ rows/sec inserts and responds to analytical queries in milliseconds. Postgres would collapse under this load.

**Consumer group:** `analytics-svc-consumer` subscribed to all three topics.

**What it writes:** Every Kafka event is materialized into a `sale_events` wide table. Dashboards query this table. No joins. No OLTP semantics.

---

## 3. Kafka — Final Topic Design

| Topic | Partitions | Partition Key | Retention | Consumers |
|---|---|---|---|---|
| `sale-events` | 8 | `saleId` | 7 days | NotificationService, AnalyticsService |
| `inventory-events` | 16 | `productId` | 3 days | OrderService, NotificationService, AnalyticsService |
| `order-events` | 8 | `saleId` | 3 days | NotificationService, AnalyticsService |
| `notifications.dlq` | 4 | none | 14 days | ops alerts only |

**Partition key rationale:**
- `inventory-events` uses `productId` (not `saleId`) to guarantee per-product ordering. Two concurrent reservations for the same product must be processed sequentially by the OrderService consumer. `saleId` would scatter them.
- `sale-events` and `order-events` use `saleId` to preserve sale-level ordering for saga correctness.

**Cardinal rule:** Kafka is async fan-out only. It is never used as synchronous RPC between services. A service that needs an immediate answer calls an HTTP endpoint. A service that needs to notify others of a state change publishes an event.

---

## 4. Redis — Three-Layer Contract

### Layer 1 — Stock Counter (InventoryService owns)

```
Key:       stock:{saleId}
Type:      String (integer)
Value:     current available stock
TTL:       sale_end_time + 10 minutes
Operation: Lua atomic DECR (see §2.2)
Fallback:  SELECT FOR UPDATE in Postgres
Eviction:  Must never be evicted mid-sale — TTL is always > 0 while sale is ACTIVE
```

### Layer 2 — Rate Limiter (API Gateway / SaleService owns)

```
Key:       rate:{userId}:{window_minute}
Type:      Sorted Set (sliding window) or String (fixed window)
TTL:       60 seconds
Algorithm: Sliding window — ZADD + ZREMRANGEBYSCORE + ZCARD in pipeline
Limit:     10 requests per user per minute on reservation endpoints
Fallback:  Circuit breaker → fail-open with audit log (rate limiter failure must not block sales)
```

### Layer 3 — Session & Idempotency Cache (OrderService owns)

```
Key (session):     session:{userId}
Type:              Hash (token, cart state, last active)
TTL:               5 minutes rolling (reset on activity)

Key (idempotency): idem:{idempotencyKey}
Type:              String (serialized response JSON)
TTL:               24 hours
Fallback:          Postgres idempotency table (source of truth)
```

**Operational contracts:**
- Eviction policy: `allkeys-lru`, 4 GB cap
- Topology: Redis Cluster, 3 primary shards with 1 replica each
- Persistence: AOF `everysec` — at most 1 second of data loss, acceptable since Postgres is the durable source of truth
- Stampede guard: probabilistic early refresh on stock counter as TTL approaches

**Cardinal rule:** Redis is never the source of truth. Every Redis key has a Postgres fallback. A total Redis failure must degrade performance, not corrupt data.

---

## 5. Database Ownership

| Schema | Owner | Key Tables |
|---|---|---|
| `sales_db` | SaleService | `flash_sales`, `sale_schedules` |
| `inventory_db` | InventoryService | `products`, `stock_levels`, `reservations` |
| `orders_db` | OrderService | `orders`, `order_outbox`, `idempotency_keys` |
| ClickHouse | AnalyticsService | `sale_events` (wide columnar table) |

NotificationService has no persistent storage. If it needs to track delivery status, that is a future bounded context and gets its own schema then.

**Zero cross-service joins.** If ServiceA needs data owned by ServiceB, the options are:
1. ServiceB publishes an event that ServiceA consumes and materializes locally (preferred for eventually-consistent data)
2. ServiceA calls ServiceB's read API (acceptable for synchronous, user-facing reads)
3. Never: ServiceA queries ServiceB's database directly

---

## 6. ADR Log (Additive — v1 ADRs Remain Valid)

| ADR | Decision | Supersedes |
|---|---|---|
| ADR-009 | 5 services is the final count, not 3 | ADR-005 (3 services) |
| ADR-010 | SaleService is a separate bounded context; flash sale lifecycle is not an inventory concern | — |
| ADR-011 | AnalyticsService uses ClickHouse; analytical workloads must not share Postgres with OLTP | — |
| ADR-012 | Choreography-based saga; no central orchestrator at 5-service scale | — |
| ADR-013 | `inventory-events` partitioned by `productId`, not `saleId`, for per-product ordering | Overrides ADR-006 for this specific topic |

**ADR-005 retirement note:** The principle behind ADR-005 ("no sprawl without justification") is preserved. ADR-009 does not abandon that principle — it applies it. Both new services passed the justification threshold: distinct domain, divergent scaling profile, zero coupling to the transactional hot path.

---

## 7. What v1 Got Right (Do Not Change)

The following v1 decisions are confirmed final and carry forward unchanged:

- Lua atomic stock decrement (ADR-001)
- Java 21 virtual threads over WebFlux (ADR-002)
- Kafka for all inter-service async communication (ADR-003)
- Transactional Outbox pattern (ADR-004)
- Database-per-service (ADR-008)
- Idempotency-Key header on all mutating endpoints
- Choreography-based saga (not orchestration)
- `SELECT FOR UPDATE` as the Redis fallback, never a stock guess

---

## 8. Interview Talking Points

The contradiction between v1 and the council session is itself an interview-grade topic. If asked:

> "You started with 3 services and ended with 5. Was the original design wrong?"

**Answer:** No. The original design applied the right principle (avoid sprawl) to a scoped problem statement. The council session expanded the problem statement — independent scaling of the sale lifecycle, analytics isolation — which justified two additional services. Good architecture is not about picking a number; it is about applying consistent criteria. The criteria here are: distinct domain, divergent scaling, independent failure mode. Both new services meet all three. A 6th service that doesn't meet those criteria gets rejected.

---

## 9. Measurable Outcomes

| Metric | Target | How |
|---|---|---|
| Reservation latency P99 | < 50ms | Redis Lua DECR; no DB on hot path |
| Oversell rate | 0 | Lua atomicity + Postgres fallback with SELECT FOR UPDATE |
| Order idempotency | 100% | Redis + Postgres dual-layer idempotency key check |
| Event delivery | At-least-once | Transactional Outbox + Kafka consumer acks |
| Sale start stampede | Handled | Redis stock pre-warmed 60s before sale_start |
| Analytics lag | < 5 seconds | ClickHouse Kafka consumer, batch insert every 1s |

---

*Document generated by council session. All five engineers — Jordan Wei (Google), Priya Nair (Uber), Marcus Shaw (Amazon), Elena Kovac (Atlassian) — and the Staff Engineer mentor are aligned on this specification.*

*Next steps: generate service skeletons, write integration tests for the saga flow, implement the Lua script with property-based tests.*