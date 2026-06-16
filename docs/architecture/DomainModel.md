# DomainModel.md
## Flash Sale Platform — Domain Model
**Version:** 1.0 | **Status:** Final
**Date:** 2026-06-15
**Architecture reference:** Final-Spec-Council.md v2.0
**Council:** Jordan Wei (Google), Priya Nair (Uber), Marcus Shaw (Amazon), Elena Kovac (Atlassian)

> **Constraint:** Service boundaries are unchanged from Final-Spec-Council.md.
> This document defines the DDD model within those boundaries — aggregates,
> entities, value objects, bounded contexts, and context map.

---

## Table of Contents

1. [Domain Model Philosophy](#1-domain-model-philosophy)
2. [Aggregates](#2-aggregates)
3. [Entities](#3-entities)
4. [Value Objects](#4-value-objects)
5. [Bounded Contexts](#5-bounded-contexts)
6. [Context Map](#6-context-map)
7. [Ubiquitous Language](#7-ubiquitous-language)
8. [Anti-Corruption Layer](#8-anti-corruption-layer)
9. [Domain Events](#9-domain-events)
10. [Java 21 Implementation Notes](#10-java-21-implementation-notes)

---

## 1. Domain Model Philosophy

**One sentence:** Model only what enforces an invariant — every aggregate root must protect
a rule that cannot be enforced elsewhere, every entity must have a lifecycle that matters,
every value object must carry behaviour beyond what a primitive provides.

### Council split criteria for an aggregate root

All three must be true:

| Criterion | Question |
|---|---|
| Invariant ownership | Does it protect a rule that would be violated without the boundary? |
| Lifecycle independence | Does it have transitions that are meaningful on their own? |
| Team ownership | Does exactly one team own its mutation? |

Applying these criteria to the Flash Sale domain yields **four aggregate roots**. The council
debated three vs four — the deciding argument was that `Reservation` has its own state machine
(`PENDING → CONFIRMED → EXPIRED → RELEASED`), its own expiry behaviour, and its own failure
mode independent of `Product`. A reservation can expire while the product still has stock.
That is a lifecycle that cannot be a child entity.

---

## 2. Aggregates

### 2.1 FlashSale — SaleContext

**Owning service:** SaleService
**Owning schema:** `sales_db`

**Core invariant:** The sale status machine is the only valid transition path.
No state can be skipped. No reverse transition except `ACTIVE → ENDED` via admin.

```
SCHEDULED ──(saleStart reached)──► ACTIVE ──(saleEnd / stock=0 / admin)──► ENDED
                                                                               │
                                                                        ARCHIVED (async)
```

**Aggregate boundary:** `FlashSale` owns `SaleSchedule` (entity). Nothing outside this
aggregate may mutate the sale status directly. All status transitions are commands on the
aggregate root.

**Commands:**
- `ScheduleSale(name, productId, totalStock, saleWindow)`
- `ActivateSale()` — triggered by Scheduler at saleStart
- `EndSale(reason)` — triggered by Scheduler or admin
- `ArchiveSale()` — triggered async post-end

**Domain events emitted:**
- `SaleScheduled`
- `SaleStarted`
- `SaleEnded`
- `SaleArchived`

---

### 2.2 Product — InventoryContext

**Owning service:** InventoryService
**Owning schema:** `inventory_db`

**Core invariant:** Total stock allocated across all active sales for this product must
never exceed available stock. A product cannot be over-allocated.

**Aggregate boundary:** `Product` owns `StockLevel` (entity, one per sale). Stock mutations
go through the aggregate root. Direct writes to `StockLevel` outside the aggregate are
an architectural violation.

**Commands:**
- `AllocateStock(saleId, quantity)` — sets up StockLevel for a sale
- `ReleaseStock(saleId)` — restores allocation on sale end
- `ReconcileStock(saleId, actual)` — corrects drift between Redis and Postgres

**Domain events emitted:**
- `StockAllocated`
- `StockReleased`
- `StockReconciled`

---

### 2.3 Reservation — InventoryContext

**Owning service:** InventoryService
**Owning schema:** `inventory_db`

**Core invariant:** A reservation holds stock for exactly one user for a finite window.
A user may not hold more than one active reservation for the same sale.

**Why a separate aggregate root and not a child of Product:**
A `Reservation` has an independent lifecycle. It can expire while the product still has
stock. It can be cancelled by the user. It can be converted to an order. None of these
transitions affect `Product` directly — they trigger compensating events. Embedding
`Reservation` inside `Product` would mean the Product aggregate must be loaded and locked
for every reservation status change, creating a concurrency bottleneck that the Lua script
was specifically designed to avoid.

```
PENDING ──(order placed)──► CONFIRMED
        ──(TTL elapsed)───► EXPIRED
        ──(user cancel)───► RELEASED
```

**Commands:**
- `CreateReservation(userId, saleId, productId, quantity, expiry)`
- `ConfirmReservation(orderId)`
- `ExpireReservation()` — triggered by TTL scheduler
- `ReleaseReservation(reason)` — triggered by saga compensation

**Domain events emitted:**
- `StockReserved` — partition key: `productId`
- `ReservationConfirmed`
- `ReservationExpired`
- `ReservationReleased`

---

### 2.4 Order — OrderContext

**Owning service:** OrderService
**Owning schema:** `orders_db`

**Core invariant:** Exactly one `Order` may exist per `IdempotencyKey`. No duplicate
orders under any failure or retry scenario.

**Aggregate boundary:** `Order` owns `OutboxEvent` (entity) and `IdempotencyRecord`
(entity). The outbox is part of the aggregate — publishing an event is not a side effect
outside the boundary; it is a first-class part of order creation.

```
PENDING ──(outbox published)──► CONFIRMED
        ──(payment failed)────► CANCELLED
        ──(timeout)───────────► EXPIRED
```

**Commands:**
- `PlaceOrder(purchaseIntentId, userId, saleId, amount, idempotencyKey)`
- `ConfirmOrder()` — triggered by payment success (stub)
- `CancelOrder(reason)` — triggered by saga compensation
- `ExpireOrder()` — triggered by timeout scheduler

**Domain events emitted:**
- `OrderCreated` — partition key: `saleId`
- `OrderConfirmed`
- `OrderCancelled`
- `ReservationReleased` — compensation event, triggers InventoryService

---

## 3. Entities

Entities have identity, mutable state, and a lifecycle that matters within their
owning aggregate. They are never referenced directly from outside their aggregate —
only via the aggregate root.

### 3.1 SaleSchedule (inside FlashSale)

**Identity:** `scheduleId: SaleScheduleId`
**Owning aggregate:** `FlashSale`

Holds the timing configuration for the sale. Can be rescheduled while the sale is
in `SCHEDULED` status. Becomes immutable once the sale transitions to `ACTIVE`.

**Key fields:** `saleStart: Instant`, `saleEnd: Instant`, `timezone: ZoneId`,
`version: long` (optimistic lock for reschedule)

---

### 3.2 StockLevel (inside Product)

**Identity:** `stockLevelId: StockLevelId` (composite: `productId + saleId`)
**Owning aggregate:** `Product`

Records the stock allocated to a specific sale. Mutable only via the Lua script
in Redis (hot path) or `SELECT FOR UPDATE` in Postgres (fallback). No other
write path is permitted.

**Key fields:** `saleId: SaleId`, `totalAllocated: StockCount`,
`currentStock: StockCount`, `version: long`

> **Priya's note:** `StockLevel` is named as an entity in the domain model for
> clarity, but operationally its mutation is delegated to the Lua script.
> The Postgres row is the durable record; Redis is the fast projection.

---

### 3.3 OutboxEvent (inside Order)

**Identity:** `outboxEventId: OutboxEventId`
**Owning aggregate:** `Order`

Tracks the Kafka publish state for each domain event the Order must emit.
Written in the same DB transaction as the Order row. Polled and published by
the outbox scheduler.

**Key fields:** `eventType: String`, `payload: JsonNode`,
`published: boolean`, `createdAt: Instant`, `publishedAt: Instant`

---

### 3.4 IdempotencyRecord (inside Order)

**Identity:** `idempotencyKey: IdempotencyKey`
**Owning aggregate:** `Order`

Stores the serialised response for a completed order creation. Checked before
any processing to short-circuit duplicates. Two-layer: Redis (fast, 24h TTL)
and Postgres (durable, permanent).

**Key fields:** `idempotencyKey: IdempotencyKey`, `responsePayload: String`,
`httpStatus: int`, `createdAt: Instant`, `expiresAt: Instant`

---

## 4. Value Objects

Value objects are immutable. Equality is by value, not identity. Every value object
carries behaviour — if it only wraps a primitive with no methods, it is not worth
the ceremony.

### 4.1 Typed Identity Objects

All five IDs are typed value objects. **A raw `UUID` is never passed across a method
boundary where a typed ID is expected.** This eliminates the class of bug where
`orderId` is accidentally passed where `reservationId` is required.

| Value Object | Wraps | Behaviour |
|---|---|---|
| `SaleId` | UUID | `toString()`, equals by value |
| `ProductId` | UUID | `toString()`, equals by value |
| `OrderId` | UUID | `toString()`, equals by value |
| `ReservationId` | UUID | `toString()`, equals by value |
| `UserId` | UUID | `toString()`, equals by value |

```java
// Java 21 — sealed interface with record implementation
public record SaleId(UUID value) {
    public SaleId {
        Objects.requireNonNull(value, "SaleId must not be null");
    }
    public static SaleId generate() { return new SaleId(UUID.randomUUID()); }
    public static SaleId of(String s) { return new SaleId(UUID.fromString(s)); }
    @Override public String toString() { return value.toString(); }
}
```

---

### 4.2 SaleWindow

**Owning aggregate:** `FlashSale`
**Invariant:** `end` must be strictly after `start`. Duration must be > 0.

```java
public record SaleWindow(Instant start, Instant end) {
    public SaleWindow {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        if (!end.isAfter(start))
            throw new IllegalArgumentException("SaleWindow end must be after start");
    }
    public boolean isOpen(Instant now)     { return !now.isBefore(start) && now.isBefore(end); }
    public boolean isUpcoming(Instant now) { return now.isBefore(start); }
    public boolean hasPassed(Instant now)  { return !now.isBefore(end); }
    public Duration duration()             { return Duration.between(start, end); }
}
```

---

### 4.3 StockCount

**Owning aggregate:** `Product`, `Reservation`
**Invariant:** Value must be ≥ 0. Negative stock is a domain violation.

```java
public record StockCount(int value) {
    public StockCount {
        if (value < 0)
            throw new IllegalArgumentException("StockCount cannot be negative: " + value);
    }
    public boolean isAvailable()           { return value > 0; }
    public boolean isSoldOut()             { return value == 0; }
    public StockCount decrement(int n)     { return new StockCount(value - n); }
    public StockCount increment(int n)     { return new StockCount(value + n); }
    public boolean canDecrement(int n)     { return value >= n; }
    public static StockCount of(int v)     { return new StockCount(v); }
    public static StockCount zero()        { return new StockCount(0); }
}
```

---

### 4.4 Quantity

**Owning aggregate:** `Reservation`
**Invariant:** Must be ≥ 1. A reservation of zero units is nonsensical.

```java
public record Quantity(int value) {
    public Quantity {
        if (value < 1)
            throw new IllegalArgumentException("Quantity must be at least 1");
    }
    public static Quantity of(int v)  { return new Quantity(v); }
    public static Quantity one()      { return new Quantity(1); }
}
```

---

### 4.5 ReservationExpiry

**Owning aggregate:** `Reservation`
**Invariant:** Expiry must be in the future at time of reservation creation.

```java
public record ReservationExpiry(Instant expiresAt) {
    public ReservationExpiry {
        Objects.requireNonNull(expiresAt);
    }
    public boolean isExpired(Instant now)       { return now.isAfter(expiresAt); }
    public boolean isValid(Instant now)         { return !isExpired(now); }
    public Duration remainingTtl(Instant now)   { return Duration.between(now, expiresAt); }
    public static ReservationExpiry in(Duration d, Instant from) {
        return new ReservationExpiry(from.plus(d));
    }
}
```

---

### 4.6 Money

**Owning aggregate:** `Order`
**Invariant:** Amount must be ≥ 0. Currency must not be null.

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (amount.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Money amount cannot be negative");
    }
    public boolean isPositive()              { return amount.compareTo(BigDecimal.ZERO) > 0; }
    public Money add(Money other) {
        if (!this.currency.equals(other.currency))
            throw new IllegalArgumentException("Cannot add Money of different currencies");
        return new Money(this.amount.add(other.amount), this.currency);
    }
    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }
}
```

---

### 4.7 IdempotencyKey

**Owning aggregate:** `Order`
**Invariant:** Must be a valid UUID v4. TTL contract is 24 hours.

```java
public record IdempotencyKey(String value) {
    private static final Duration TTL = Duration.ofHours(24);

    public IdempotencyKey {
        Objects.requireNonNull(value);
        if (value.isBlank())
            throw new IllegalArgumentException("IdempotencyKey must not be blank");
    }
    public boolean isSameRequest(IdempotencyKey other) { return this.equals(other); }
    public Instant expiresAt(Instant createdAt)        { return createdAt.plus(TTL); }
    public boolean isExpired(Instant createdAt, Instant now) {
        return now.isAfter(expiresAt(createdAt));
    }
    public static IdempotencyKey of(String v) { return new IdempotencyKey(v); }
}
```

---

## 5. Bounded Contexts

Each service is exactly one bounded context. The ubiquitous language within a context
is authoritative for that context only. The same word in two contexts may mean different
things — this is expected and correct, not a naming problem to be fixed.

### 5.1 SaleContext

**Service:** SaleService
**Language:** sale, status, schedule, activation, sale window, sale lifecycle

| Term | Meaning in this context |
|---|---|
| `FlashSale` | The authoritative sale entity. Source of truth for status. |
| `SaleStatus` | SCHEDULED / ACTIVE / ENDED / ARCHIVED — the machine, not a label. |
| `SaleWindow` | Start + end with business rules. Not a pair of timestamps. |
| `SaleSchedule` | The timing configuration. Can be rescheduled pre-activation. |
| `activate` | Transition SCHEDULED → ACTIVE. Triggered by the Scheduler actor. |
| `end` | Transition ACTIVE → ENDED. Triggered by time, stock depletion, or admin. |

---

### 5.2 InventoryContext

**Service:** InventoryService
**Language:** product, stock, reservation, decrement, allocation, expiry

| Term | Meaning in this context |
|---|---|
| `Product` | The entity that owns stock. Does not know what a FlashSale is. |
| `StockLevel` | Per-sale stock record within a Product. |
| `Reservation` | A timed stock hold for a specific user and sale. |
| `decrement` | Atomic Lua operation. Not a generic update. |
| `SaleId` | A reference only — InventoryContext does not import SaleContext types. |
| `expiry` | The moment a Reservation becomes invalid and stock is released. |

---

### 5.3 OrderContext

**Service:** OrderService
**Language:** order, purchase intent, idempotency, outbox, confirmation, saga

| Term | Meaning in this context |
|---|---|
| `Order` | A confirmed purchase record. One per IdempotencyKey. |
| `PurchaseIntent` | OrderContext's translation of InventoryContext's Reservation. |
| `IdempotencyRecord` | The stored response for a processed request. Not a cache entry. |
| `OutboxEvent` | A domain event awaiting Kafka publication. Part of the Order aggregate. |
| `place` | The command to create an Order. Idempotent by design. |
| `confirm` | Transition PENDING → CONFIRMED after payment success. |

> **Note:** OrderContext deliberately does not use the word "Reservation."
> It uses "PurchaseIntent" instead. This is not a naming preference — it is
> an explicit anti-corruption boundary.

---

### 5.4 NotificationContext

**Service:** NotificationService
**Language:** event, channel, payload, delivery, dispatch, DLQ

| Term | Meaning in this context |
|---|---|
| `NotificationEvent` | An upstream Kafka event translated into a dispatch instruction. |
| `DeliveryChannel` | EMAIL / PUSH / SMS. Where to send. |
| `NotificationPayload` | The assembled message. Derived, not stored. |
| `dispatch` | The act of sending via a provider. Not "publish" — that is Kafka vocabulary. |
| `DlqEvent` | A failed dispatch written to the dead-letter queue after exhausted retries. |

This context is **Conformist** — it adopts the upstream event model as-is. It has no
domain rules of its own. It does not translate terminology; it uses upstream terms
directly in its event consumers.

---

### 5.5 AnalyticsContext

**Service:** AnalyticsService
**Language:** event projection, ingestion, metric, wide table, read model

| Term | Meaning in this context |
|---|---|
| `SaleEvent` | A wide columnar row in ClickHouse materialized from any upstream Kafka event. |
| `EventProjection` | The mapping from upstream Kafka schema to ClickHouse columns. |
| `SaleMetrics` | Aggregate query results over SaleEvent. Dashboards read from here. |
| `ingest` | Consume a Kafka event and write it to ClickHouse. The only mutation. |

This context is **Conformist** — it consumes events exactly as published. It has no
business rules. If the upstream event schema changes, AnalyticsContext adapts.

---

## 6. Context Map

### Integration Pattern Summary

| Upstream | Downstream | Pattern | Channel | Notes |
|---|---|---|---|---|
| SaleContext | InventoryContext | Partnership | Kafka `sale-events` | Co-evolve on sale model changes |
| InventoryContext | OrderContext | Customer/Supplier + ACL | Kafka `inventory-events` | ReservationId translated to PurchaseIntentId |
| SaleContext | NotificationContext | Conformist | Kafka `sale-events` | No translation |
| InventoryContext | NotificationContext | Conformist | Kafka `inventory-events` | No translation |
| OrderContext | NotificationContext | Conformist | Kafka `order-events` | No translation |
| SaleContext | AnalyticsContext | Conformist | Kafka `sale-events` | Projected to ClickHouse |
| InventoryContext | AnalyticsContext | Conformist | Kafka `inventory-events` | Projected to ClickHouse |
| OrderContext | AnalyticsContext | Conformist | Kafka `order-events` | Projected to ClickHouse |

### Pattern Definitions

**Partnership:** Both contexts co-evolve. A schema change in SaleContext that affects how
InventoryContext reads sale status is coordinated between both teams before deployment.
Neither is upstream or downstream — both must agree on changes.

**Customer/Supplier:** InventoryContext (Supplier) publishes events. OrderContext (Customer)
consumes them. The Customer has a voice in what the Supplier publishes (can request event
schema additions) but the Supplier controls the canonical model. An ACL protects OrderContext
from being coupled to InventoryContext's internal terminology.

**Conformist:** NotificationContext and AnalyticsContext conform to whatever upstream
publishes. They have no negotiating power and no business rules that could conflict.
This is the correct pattern for pure consumer contexts.

---

## 7. Ubiquitous Language

### Terms and their context scope

| Term | SaleContext | InventoryContext | OrderContext | NotifContext | AnalyticsContext |
|---|---|---|---|---|---|
| `sale` | FlashSale entity | A SaleId reference only | A SaleId reference only | Received via event | Projected to column |
| `reservation` | Not used | Aggregate root | `PurchaseIntent` (translated) | Received via event | Projected to column |
| `stock` | Not used | Core domain concept | Not used | Not used | Projected to column |
| `order` | Not used | Not used | Aggregate root | Received via event | Projected to column |
| `confirm` | Not used | Reservation confirmed | Order confirmed | Dispatches notification | Not used |
| `expiry` | SaleWindow.hasPassed() | ReservationExpiry | IdempotencyKey.isExpired() | Not used | Not used |

---

## 8. Anti-Corruption Layer

### ACL: InventoryContext → OrderContext

The only ACL in the system. OrderService's Kafka consumer translates
`inventory-events` into OrderContext's own model before any domain logic runs.

```
InventoryContext term       ACL translation        OrderContext term
─────────────────────────────────────────────────────────────────────
Reservation                 →  translate()  →      PurchaseIntent
ReservationId               →  wrap()        →     PurchaseIntentId
StockReserved event         →  map()         →     PurchaseConfirmation
ReservationExpiry.isExpired →  check()       →     PurchaseIntent.isStillValid()
PENDING status              →  interpret()   →     OPEN state
CONFIRMED status            →  interpret()   →     CONSUMED state
EXPIRED status              →  interpret()   →     LAPSED state
```

**Implementation pattern:**

```java
// In OrderService Kafka consumer — translates before domain logic
@Component
public class InventoryEventTranslator {

    public PurchaseIntent translate(StockReservedEvent event) {
        return PurchaseIntent.builder()
            .purchaseIntentId(PurchaseIntentId.fromReservation(event.reservationId()))
            .userId(UserId.of(event.userId()))
            .saleId(SaleId.of(event.saleId()))
            .quantity(Quantity.of(event.quantity()))
            .validUntil(ReservationValidity.until(event.expiresAt()))
            .build();
    }
}
```

**Why this ACL is non-negotiable:** Without it, OrderService's domain model
becomes semantically coupled to InventoryService. A rename of `Reservation` to
`StockHold` in InventoryContext would require changes in OrderService. The ACL
makes that change invisible to OrderContext.

---

## 9. Domain Events

All domain events follow the same envelope contract.

### Envelope schema

```java
public record DomainEvent<T>(
    String       eventId,        // UUID v4 — deduplication key for consumers
    String       eventType,      // e.g. "StockReserved"
    String       eventVersion,   // "1.0" — backward-compatible versioning
    Instant      occurredAt,     // When the domain event happened
    String       aggregateId,    // Root entity ID
    String       aggregateType,  // e.g. "Reservation"
    T            payload         // Event-specific data
) {}
```

### Event catalogue

| Event | Emitter | Kafka Topic | Partition Key | Trigger |
|---|---|---|---|---|
| `SaleScheduled` | SaleContext | `sale-events` | `saleId` | Sale created |
| `SaleStarted` | SaleContext | `sale-events` | `saleId` | SCHEDULED → ACTIVE |
| `SaleEnded` | SaleContext | `sale-events` | `saleId` | ACTIVE → ENDED |
| `StockAllocated` | InventoryContext | `inventory-events` | `productId` | Stock set for sale |
| `StockReserved` | InventoryContext | `inventory-events` | `productId` | Lua decrement success |
| `ReservationExpired` | InventoryContext | `inventory-events` | `productId` | TTL elapsed |
| `ReservationReleased` | InventoryContext | `inventory-events` | `productId` | Saga compensation |
| `OrderCreated` | OrderContext | `order-events` | `saleId` | Order placed |
| `OrderConfirmed` | OrderContext | `order-events` | `saleId` | Payment success |
| `OrderCancelled` | OrderContext | `order-events` | `saleId` | Saga compensation |

### Event versioning contract

- `eventVersion: "1.0"` — initial schema
- Non-breaking additions (new optional fields) increment minor: `"1.1"`
- Breaking changes (removed or renamed fields) require a new `eventType`
- Consumers must ignore unknown fields — Jackson `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`

---

## 10. Java 21 Implementation Notes

### Record-based value objects

All value objects are Java 21 `record` types. Records are immutable by default,
provide `equals()`, `hashCode()`, and `toString()` for free, and signal intent clearly.

```java
// Compact constructor for validation
public record StockCount(int value) {
    public StockCount {                          // compact constructor
        if (value < 0) throw new IllegalArgumentException("...");
    }
}
```

### Sealed interfaces for aggregate status

Java 21 sealed interfaces + records model state machines without nulls or stringly-typed enums.

```java
public sealed interface SaleStatus
    permits SaleStatus.Scheduled, SaleStatus.Active,
            SaleStatus.Ended, SaleStatus.Archived {

    record Scheduled(Instant scheduledAt) implements SaleStatus {}
    record Active(Instant activatedAt)   implements SaleStatus {}
    record Ended(Instant endedAt, EndReason reason) implements SaleStatus {}
    record Archived(Instant archivedAt)  implements SaleStatus {}
}

// Pattern matching in aggregate commands — Java 21
public FlashSale activate(Instant now) {
    return switch (this.status) {
        case SaleStatus.Scheduled s -> this.withStatus(new SaleStatus.Active(now));
        case SaleStatus.Active a    -> throw new IllegalStateException("Already active");
        case SaleStatus.Ended e     -> throw new IllegalStateException("Sale has ended");
        case SaleStatus.Archived a  -> throw new IllegalStateException("Sale is archived");
    };
}
```

### Package structure per bounded context

```
com.flashsale.
├── sale/
│   ├── domain/
│   │   ├── aggregate/   FlashSale.java
│   │   ├── entity/      SaleSchedule.java
│   │   ├── vo/          SaleWindow.java, SaleId.java, SaleStatus.java
│   │   └── event/       SaleStarted.java, SaleEnded.java
│   ├── application/     SaleCommandService.java, SaleQueryService.java
│   └── infra/           SaleRepository.java, SaleEventPublisher.java
├── inventory/
│   ├── domain/
│   │   ├── aggregate/   Product.java, Reservation.java
│   │   ├── entity/      StockLevel.java
│   │   ├── vo/          StockCount.java, ReservationExpiry.java,
│   │   │                Quantity.java, ProductId.java, ReservationId.java
│   │   └── event/       StockReserved.java, ReservationExpired.java
│   ├── application/     ReservationCommandService.java
│   └── infra/           StockLuaScript.java, ReservationRepository.java
├── order/
│   ├── domain/
│   │   ├── aggregate/   Order.java
│   │   ├── entity/      OutboxEvent.java, IdempotencyRecord.java
│   │   ├── vo/          Money.java, IdempotencyKey.java, OrderId.java
│   │   └── event/       OrderCreated.java, OrderCancelled.java
│   ├── application/     OrderCommandService.java
│   └── infra/           OrderRepository.java, OutboxPoller.java,
│                        InventoryEventTranslator.java   ← ACL lives here
├── notification/
│   ├── domain/
│   │   └── vo/          DeliveryChannel.java, NotificationPayload.java
│   └── infra/           NotificationEventConsumer.java
└── analytics/
    ├── domain/
    │   └── projection/  SaleEventProjection.java
    └── infra/           AnalyticsEventConsumer.java, ClickHouseWriter.java
```

---

*Domain model derived from council debate. Service boundaries from Final-Spec-Council.md v2.0 unchanged.*
*Next steps: service skeletons with domain package structure, aggregate unit tests,
integration test for reservation saga flow.*