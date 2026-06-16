# DatabaseSchema.md
## Flash Sale Platform — PostgreSQL Schema Design
**Version:** 1.0 | **Status:** Final
**Date:** 2026-06-15
**Sources:** Final-Spec-Council.md v2.0 · DomainModel.md v1.0
**Engine:** PostgreSQL 16+

---

## Table of Contents

1. [Schema Overview](#1-schema-overview)
2. [sales_db](#2-sales_db)
3. [inventory_db](#3-inventory_db)
4. [orders_db](#4-orders_db)
5. [Cross-Database Relationships](#5-cross-database-relationships)
6. [Index Strategy](#6-index-strategy)
7. [Constraint Catalogue](#7-constraint-catalogue)
8. [Query Patterns](#8-query-patterns)
9. [Design Decisions](#9-design-decisions)

---

## 1. Schema Overview

### Database ownership

| Database     | Owner Service      | Aggregates Persisted                         |
|--------------|--------------------|----------------------------------------------|
| `sales_db`   | SaleService        | FlashSale, SaleSchedule, SaleStatusHistory   |
| `inventory_db` | InventoryService | Product, StockLevel, Reservation, StockLog   |
| `orders_db`  | OrderService       | Order, OrderOutbox, IdempotencyRecord        |

### Relationship type key

```
─────────  FK within same database (enforced by Postgres)
- - - - -  Opaque reference across databases (UUID only, no FK constraint)
```

### Full entity-relationship map

```
sales_db
──────────────────────────────────────────────
flash_sales ──────────< sale_schedules         (1:1, FK enforced)
flash_sales ──────────< sale_status_history    (1:N, FK enforced)

inventory_db
──────────────────────────────────────────────
products ─────────────< stock_levels           (1:N, FK enforced)
products ─────────────< reservations           (1:N, FK enforced)
products ─────────────< stock_reservation_log  (1:N, FK enforced)

orders_db
──────────────────────────────────────────────
orders ───────────────< order_outbox           (1:N, FK enforced)
orders ───────────────< idempotency_keys       (1:1 optional, FK enforced)

Cross-database opaque references (UUID, no FK)
──────────────────────────────────────────────
flash_sales.product_id          - - → products.id
stock_levels.sale_id            - - → flash_sales.id
reservations.sale_id            - - → flash_sales.id
reservations.order_id           - - → orders.id
orders.sale_id                  - - → flash_sales.id
orders.reservation_id           - - → reservations.id
```

---

## 2. sales_db

### 2.1 flash_sales

Maps to the `FlashSale` aggregate root. The single most-read table in `sales_db` —
`GET /api/v1/sales/{id}/active` is Redis-first, but falls back to this table.

| Column         | Type         | Nullable | Notes                                      |
|----------------|--------------|----------|--------------------------------------------|
| id             | UUID         | NOT NULL | PK, gen_random_uuid()                      |
| name           | VARCHAR(255) | NOT NULL |                                            |
| product_id     | UUID         | NOT NULL | Opaque ref to inventory_db.products        |
| total_stock    | INTEGER      | NOT NULL | CHECK > 0                                  |
| status         | VARCHAR(20)  | NOT NULL | SCHEDULED \| ACTIVE \| ENDED \| ARCHIVED   |
| scheduled_at   | TIMESTAMPTZ  | NOT NULL | DEFAULT NOW()                              |
| activated_at   | TIMESTAMPTZ  | NULL     | Set on SCHEDULED → ACTIVE                  |
| ended_at       | TIMESTAMPTZ  | NULL     | Set on ACTIVE → ENDED                      |
| archived_at    | TIMESTAMPTZ  | NULL     | Set on ENDED → ARCHIVED                    |
| end_reason     | VARCHAR(50)  | NULL     | TIME_ELAPSED \| STOCK_DEPLETED \| ADMIN_FORCE |
| version        | BIGINT       | NOT NULL | Optimistic lock, incremented per transition |
| created_at     | TIMESTAMPTZ  | NOT NULL | DEFAULT NOW()                              |
| updated_at     | TIMESTAMPTZ  | NOT NULL | Managed by trigger                         |

**Constraints:**
- `flash_sales_status_ck` — status must be one of the four valid states
- `flash_sales_total_stock_positive_ck` — total_stock > 0
- `flash_sales_activated_at_ck` — activated_at is NULL iff status = SCHEDULED

**Indexes:**
```sql
-- Scheduler activation sweep (partial — only SCHEDULED rows)
idx_flash_sales_status_scheduled_at  ON (status, scheduled_at) WHERE status = 'SCHEDULED'

-- Scheduler end sweep (partial — only ACTIVE rows)
idx_flash_sales_status_ended_at      ON (status, ended_at) WHERE status = 'ACTIVE'

-- Admin: all sales for a product
idx_flash_sales_product_id           ON (product_id)

-- Admin dashboard: sales by status, recency-sorted
idx_flash_sales_status_created_at    ON (status, created_at DESC)
```

---

### 2.2 sale_schedules

Maps to the `SaleSchedule` entity inside `FlashSale`. Holds the `SaleWindow` value
object fields (`sale_start`, `sale_end`, `timezone`).

| Column      | Type         | Nullable | Notes                                        |
|-------------|--------------|----------|----------------------------------------------|
| id          | UUID         | NOT NULL | PK                                           |
| sale_id     | UUID         | NOT NULL | FK → flash_sales.id, UNIQUE (one per sale)   |
| sale_start  | TIMESTAMPTZ  | NOT NULL | SaleWindow.start                             |
| sale_end    | TIMESTAMPTZ  | NOT NULL | SaleWindow.end                               |
| timezone    | VARCHAR(64)  | NOT NULL | DEFAULT 'UTC'                                |
| version     | BIGINT       | NOT NULL | Optimistic lock for reschedule operations    |
| created_at  | TIMESTAMPTZ  | NOT NULL |                                              |
| updated_at  | TIMESTAMPTZ  | NOT NULL | Managed by trigger                           |

**Constraints:**
- `sale_schedules_window_ck` — sale_end > sale_start
- `sale_schedules_sale_id_unique` — one schedule per sale (UNIQUE)

**Indexes:**
```sql
-- Scheduler: upcoming sales to activate (partial — only future/recent windows)
idx_sale_schedules_sale_start  ON (sale_start) WHERE sale_start > NOW() - INTERVAL '1 hour'

-- Scheduler: active sales to end
idx_sale_schedules_sale_end    ON (sale_end)   WHERE sale_end   > NOW() - INTERVAL '1 hour'
```

**Immutability contract:** `sale_schedules` rows become logically immutable once the
parent `flash_sales.status` transitions to `ACTIVE`. The application layer enforces
this by checking `flash_sales.version` before allowing reschedule operations. The
database does not enforce this directly — it is an application-level invariant.

---

### 2.3 sale_status_history

Immutable audit log. Rows are INSERT-only — never UPDATE or DELETE. Satisfies the
admin audit trail requirement (US-004, NFR-023).

| Column           | Type         | Nullable | Notes                              |
|------------------|--------------|----------|------------------------------------|
| id               | UUID         | NOT NULL | PK                                 |
| sale_id          | UUID         | NOT NULL | FK → flash_sales.id                |
| from_status      | VARCHAR(20)  | NULL     | NULL on initial SCHEDULED entry    |
| to_status        | VARCHAR(20)  | NOT NULL | CHECK against valid statuses       |
| transitioned_at  | TIMESTAMPTZ  | NOT NULL | DEFAULT NOW()                      |
| actor            | VARCHAR(100) | NOT NULL | 'SCHEDULER' \| 'ADMIN:{userId}'    |
| reason           | VARCHAR(255) | NULL     |                                    |

**Indexes:**
```sql
-- Primary read path: GET /api/v1/sales/{id}/history
idx_sale_status_history_sale_id_time  ON (sale_id, transitioned_at DESC)
```

---

## 3. inventory_db

### 3.1 products

Maps to the `Product` aggregate root. Does not know about flash sales — holds only
the global product definition and total stock capacity.

| Column       | Type          | Nullable | Notes                               |
|--------------|---------------|----------|-------------------------------------|
| id           | UUID          | NOT NULL | PK                                  |
| name         | VARCHAR(255)  | NOT NULL |                                     |
| sku          | VARCHAR(100)  | NOT NULL | UNIQUE                              |
| description  | TEXT          | NULL     |                                     |
| base_price   | NUMERIC(12,2) | NOT NULL |                                     |
| currency     | CHAR(3)       | NOT NULL | DEFAULT 'USD'                       |
| total_stock  | INTEGER       | NOT NULL | CHECK >= 0                          |
| is_active    | BOOLEAN       | NOT NULL | DEFAULT TRUE                        |
| version      | BIGINT        | NOT NULL | Optimistic lock                     |
| created_at   | TIMESTAMPTZ   | NOT NULL |                                     |
| updated_at   | TIMESTAMPTZ   | NOT NULL | Managed by trigger                  |

---

### 3.2 stock_levels

Maps to the `StockLevel` entity inside `Product`. This is the Postgres source of
truth for stock. Redis (`stock:{saleId}`) is a projection of `current_stock`.

| Column              | Type        | Nullable | Notes                                          |
|---------------------|-------------|----------|------------------------------------------------|
| id                  | UUID        | NOT NULL | PK                                             |
| product_id          | UUID        | NOT NULL | FK → products.id                               |
| sale_id             | UUID        | NOT NULL | Opaque ref to sales_db                         |
| total_allocated     | INTEGER     | NOT NULL | CHECK > 0 — set at sale creation               |
| current_stock       | INTEGER     | NOT NULL | CHECK >= 0, <= total_allocated                 |
| redis_warmed_at     | TIMESTAMPTZ | NULL     | When Redis was last pre-warmed for this sale   |
| last_reconciled_at  | TIMESTAMPTZ | NULL     | When Redis↔Postgres reconciliation last ran    |
| version             | BIGINT      | NOT NULL | Used by SELECT FOR UPDATE fallback path        |
| created_at          | TIMESTAMPTZ | NOT NULL |                                                |
| updated_at          | TIMESTAMPTZ | NOT NULL | Managed by trigger                             |

**Constraints:**
- `stock_levels_product_sale_unique` — UNIQUE(product_id, sale_id)
- `stock_levels_stock_ceiling_ck` — current_stock <= total_allocated
- `stock_levels_current_stock_ck` — current_stock >= 0
- `stock_levels_total_allocated_ck` — total_allocated > 0

**The SELECT FOR UPDATE fallback path:**
```sql
-- Called when Redis returns -2 (cache miss) or Redis is unreachable
BEGIN;
    SELECT id, current_stock, version
    FROM   stock_levels
    WHERE  product_id = $1 AND sale_id = $2
    FOR UPDATE;                                    -- row-level lock

    -- Only if current_stock > 0:
    UPDATE stock_levels
    SET    current_stock = current_stock - $3,
           version       = version + 1,
           updated_at    = NOW()
    WHERE  id = $4 AND version = $5;              -- optimistic check
COMMIT;
```

---

### 3.3 reservations

Maps to the `Reservation` aggregate root. Critical table — the partial unique index
`idx_reservations_user_sale_active` is what enforces the "one active reservation per
user per sale" invariant at the database level.

| Column           | Type         | Nullable | Notes                                           |
|------------------|--------------|----------|-------------------------------------------------|
| id               | UUID         | NOT NULL | PK                                              |
| user_id          | UUID         | NOT NULL | Opaque ref                                      |
| sale_id          | UUID         | NOT NULL | Opaque ref to sales_db                          |
| product_id       | UUID         | NOT NULL | FK → products.id                                |
| status           | VARCHAR(20)  | NOT NULL | PENDING \| CONFIRMED \| EXPIRED \| RELEASED     |
| quantity         | INTEGER      | NOT NULL | CHECK >= 1                                      |
| expires_at       | TIMESTAMPTZ  | NOT NULL | ReservationExpiry.expiresAt                     |
| idempotency_key  | VARCHAR(255) | NOT NULL | UNIQUE — prevents duplicate reservations        |
| confirmed_at     | TIMESTAMPTZ  | NULL     | Set on → CONFIRMED                              |
| expired_at       | TIMESTAMPTZ  | NULL     | Set on → EXPIRED                                |
| released_at      | TIMESTAMPTZ  | NULL     | Set on → RELEASED                               |
| release_reason   | VARCHAR(50)  | NULL     | SAGA_COMPENSATION \| USER_CANCEL \| TIMEOUT     |
| order_id         | UUID         | NULL     | Opaque ref to orders_db, set on CONFIRMED       |
| version          | BIGINT       | NOT NULL | Optimistic lock                                 |
| created_at       | TIMESTAMPTZ  | NOT NULL |                                                 |
| updated_at       | TIMESTAMPTZ  | NOT NULL | Managed by trigger                              |

**Indexes:**
```sql
-- CRITICAL INVARIANT: one active reservation per user per sale
-- Partial unique — EXPIRED and RELEASED rows don't count toward the limit
idx_reservations_user_sale_active   ON (user_id, sale_id)
                                    WHERE status IN ('PENDING','CONFIRMED')

-- Idempotency: reject duplicate reservation requests
idx_reservations_idempotency_key    ON (idempotency_key) UNIQUE

-- TTL expiry sweep: find PENDING reservations past their TTL
idx_reservations_expiry_pending     ON (expires_at) WHERE status = 'PENDING'

-- Admin monitoring: all reservations for a sale
idx_reservations_sale_id_status     ON (sale_id, status)

-- Buyer history
idx_reservations_user_id_created_at ON (user_id, created_at DESC)
```

---

### 3.4 stock_reservation_log

Append-only audit table. Records every stock mutation for reconciliation and fraud
detection. Never read on the hot path — only by the reconciliation job and audit queries.

| Column           | Type        | Nullable | Notes                                        |
|------------------|-------------|----------|----------------------------------------------|
| id               | UUID        | NOT NULL | PK                                           |
| reservation_id   | UUID        | NOT NULL | Opaque ref                                   |
| product_id       | UUID        | NOT NULL | FK → products.id                             |
| sale_id          | UUID        | NOT NULL | Opaque ref                                   |
| user_id          | UUID        | NOT NULL | Opaque ref                                   |
| operation        | VARCHAR(20) | NOT NULL | RESERVE \| RELEASE \| RECONCILE \| EXPIRE    |
| quantity_delta   | INTEGER     | NOT NULL | Negative = decrement                         |
| stock_before     | INTEGER     | NOT NULL |                                              |
| stock_after      | INTEGER     | NOT NULL |                                              |
| source           | VARCHAR(20) | NOT NULL | REDIS \| POSTGRES_FALLBACK \| RECONCILE      |
| occurred_at      | TIMESTAMPTZ | NOT NULL | DEFAULT NOW()                                |

---

## 4. orders_db

### 4.1 orders

Maps to the `Order` aggregate root. The `idempotency_key` UNIQUE index is the
database-level enforcement of the platform's core correctness guarantee.

| Column           | Type          | Nullable | Notes                                          |
|------------------|---------------|----------|------------------------------------------------|
| id               | UUID          | NOT NULL | PK                                             |
| user_id          | UUID          | NOT NULL | Opaque ref                                     |
| sale_id          | UUID          | NOT NULL | Opaque ref to sales_db                         |
| reservation_id   | UUID          | NOT NULL | Opaque ref to inventory_db, UNIQUE             |
| status           | VARCHAR(20)   | NOT NULL | PENDING \| CONFIRMED \| CANCELLED \| EXPIRED   |
| amount           | NUMERIC(12,2) | NOT NULL | CHECK > 0 — Money.amount                       |
| currency         | CHAR(3)       | NOT NULL | DEFAULT 'USD' — Money.currency                 |
| idempotency_key  | VARCHAR(255)  | NOT NULL | UNIQUE — the core correctness guarantee        |
| confirmed_at     | TIMESTAMPTZ   | NULL     | Set on → CONFIRMED                             |
| cancelled_at     | TIMESTAMPTZ   | NULL     | Set on → CANCELLED                             |
| expired_at       | TIMESTAMPTZ   | NULL     | Set on → EXPIRED                               |
| cancel_reason    | VARCHAR(100)  | NULL     | PAYMENT_FAILED \| SAGA_COMPENSATION \| TIMEOUT |
| version          | BIGINT        | NOT NULL | Optimistic lock                                |
| created_at       | TIMESTAMPTZ   | NOT NULL |                                                |
| updated_at       | TIMESTAMPTZ   | NOT NULL | Managed by trigger                             |

**Indexes:**
```sql
-- CORE GUARANTEE: one order per idempotency key
idx_orders_idempotency_key   ON (idempotency_key) UNIQUE

-- SAGA GUARANTEE: one order per reservation
idx_orders_reservation_id    ON (reservation_id) UNIQUE

-- Buyer: order history
idx_orders_user_id_created_at  ON (user_id, created_at DESC)

-- Admin/analytics: orders per sale by status
idx_orders_sale_id_status      ON (sale_id, status)
```

---

### 4.2 order_outbox

Maps to the `OutboxEvent` entity. The most operationally critical table in `orders_db` —
the outbox poller runs every 500ms against `idx_order_outbox_unpublished`.

| Column             | Type        | Nullable | Notes                                      |
|--------------------|-------------|----------|--------------------------------------------|
| id                 | UUID        | NOT NULL | PK                                         |
| order_id           | UUID        | NOT NULL | FK → orders.id, ON DELETE RESTRICT         |
| event_id           | UUID        | NOT NULL | UNIQUE — Kafka deduplication key           |
| event_type         | VARCHAR(100)| NOT NULL | 'OrderCreated' \| 'OrderCancelled' etc.    |
| event_version      | VARCHAR(10) | NOT NULL | DEFAULT '1.0'                              |
| aggregate_id       | UUID        | NOT NULL |                                            |
| aggregate_type     | VARCHAR(50) | NOT NULL | DEFAULT 'Order'                            |
| payload            | JSONB       | NOT NULL | Full domain event payload                  |
| published          | BOOLEAN     | NOT NULL | DEFAULT FALSE                              |
| published_at       | TIMESTAMPTZ | NULL     | Set by outbox poller on success            |
| attempt_count      | SMALLINT    | NOT NULL | DEFAULT 0                                  |
| last_attempted_at  | TIMESTAMPTZ | NULL     |                                            |
| last_error         | TEXT        | NULL     | Last Kafka publish error message           |
| created_at         | TIMESTAMPTZ | NOT NULL |                                            |

**Indexes:**
```sql
-- CRITICAL: Outbox poller hot path — partial index, only unpublished rows
-- This index shrinks toward zero as the poller catches up
idx_order_outbox_unpublished  ON (created_at ASC) WHERE published = FALSE

-- Audit: all events for an order
idx_order_outbox_order_id     ON (order_id)

-- Deduplication: covered by UNIQUE constraint on event_id
```

**Outbox poller query:**
```sql
-- Runs every 500ms, batches up to 100 rows
SELECT id, order_id, event_id, event_type, event_version,
       aggregate_id, payload, attempt_count
FROM   order_outbox
WHERE  published = FALSE
ORDER BY created_at ASC
LIMIT  100
FOR UPDATE SKIP LOCKED;               -- concurrent-safe: multiple poller instances
```

`FOR UPDATE SKIP LOCKED` is mandatory — it allows multiple OrderService pods to run
the outbox poller concurrently without blocking each other.

---

### 4.3 idempotency_keys

Maps to the `IdempotencyRecord` entity. The Redis idempotency cache (Layer 3) is the
fast path; this table is the durable fallback and permanent record.

| Column            | Type         | Nullable | Notes                                      |
|-------------------|--------------|----------|--------------------------------------------|
| idempotency_key   | VARCHAR(255) | NOT NULL | PK — the key IS the identity               |
| response_payload  | TEXT         | NOT NULL | JSON-serialised HTTP response body         |
| http_status       | SMALLINT     | NOT NULL | CHECK BETWEEN 100 AND 599                  |
| order_id          | UUID         | NULL     | FK → orders.id (NULL if order failed)      |
| created_at        | TIMESTAMPTZ  | NOT NULL | DEFAULT NOW()                              |
| expires_at        | TIMESTAMPTZ  | NOT NULL | GENERATED: created_at + 24 hours           |

**Indexes:**
```sql
-- Cleanup: nightly job deletes expired keys
idx_idempotency_keys_expires_at  ON (expires_at) WHERE expires_at < NOW()

-- Reverse: which key belongs to an order?
idx_idempotency_keys_order_id    ON (order_id) WHERE order_id IS NOT NULL
```

---

## 5. Cross-Database Relationships

No foreign key constraints exist across databases. Cross-service references are opaque
UUIDs. Referential integrity across service boundaries is maintained via domain events
and the saga pattern, not database constraints.

### Reference map

| Referring column                | Target                       | Integrity mechanism              |
|---------------------------------|------------------------------|----------------------------------|
| `flash_sales.product_id`        | `inventory_db.products.id`   | Kafka `SaleScheduled` event      |
| `stock_levels.sale_id`          | `sales_db.flash_sales.id`    | Kafka `SaleStarted` event        |
| `reservations.sale_id`          | `sales_db.flash_sales.id`    | App validates via SaleService API|
| `reservations.order_id`         | `orders_db.orders.id`        | Kafka `OrderCreated` event       |
| `orders.sale_id`                | `sales_db.flash_sales.id`    | Passed through from reservation  |
| `orders.reservation_id`         | `inventory_db.reservations.id` | Validated via Kafka consumer    |

### Why no cross-DB foreign keys

Foreign keys across databases would:
1. Couple the deployment lifecycle of all three databases — a migration in `inventory_db`
   could block a deploy of `orders_db`
2. Prevent independent scaling — Postgres cannot enforce FK constraints across network
   boundaries
3. Create distributed transaction requirements — enforcing referential integrity across
   services requires 2PC, which is a known failure mode

The trade-off: referential integrity is eventually consistent, not immediately consistent.
A `reservation_id` in `orders_db` that doesn't exist in `inventory_db` is a saga
inconsistency, handled by the compensation flow.

---

## 6. Index Strategy

### Index design principles

1. **Partial indexes for hot paths.** Tables like `order_outbox` and `reservations`
   have large volumes of terminal rows (published=TRUE, status=EXPIRED). Partial
   indexes on `WHERE published = FALSE` and `WHERE status IN ('PENDING','CONFIRMED')`
   keep the working index small and fast.

2. **No index on FK opaque references unless queried.** `orders.sale_id` gets an index
   because admin queries use it. `orders.user_id` gets an index because buyers query
   their order history. `reservations.order_id` does not get its own index — it is
   only set on confirmation and never queried directly.

3. **DESC ordering on time columns for list queries.** User-facing list endpoints always
   want most-recent-first. `idx_orders_user_id_created_at ON (user_id, created_at DESC)`
   supports this with a single index scan.

4. **UNIQUE constraints as indexes.** Postgres creates a B-tree index for every UNIQUE
   constraint automatically. `idempotency_key` uniqueness is enforced by a UNIQUE index,
   not a separate constraint — same effect, one fewer object.

5. **Covering the outbox poller.** The outbox poller's query
   (`WHERE published = FALSE ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED`)
   needs only `id`, `created_at`, and `published` from the index. The partial index
   `idx_order_outbox_unpublished` covers this without a heap fetch for the WHERE clause.

### Index catalogue by criticality

| Index | Table | Type | Why Critical |
|---|---|---|---|
| `idx_orders_idempotency_key` | orders | UNIQUE | Core correctness guarantee |
| `idx_orders_reservation_id` | orders | UNIQUE | Prevents double-order per reservation |
| `idx_reservations_user_sale_active` | reservations | PARTIAL UNIQUE | Prevents double-reservation per user |
| `idx_reservations_idempotency_key` | reservations | UNIQUE | Prevents duplicate reservation on retry |
| `idx_order_outbox_unpublished` | order_outbox | PARTIAL | Outbox poller hot path, 500ms interval |
| `idx_reservations_expiry_pending` | reservations | PARTIAL | TTL sweep scheduler |
| `idx_flash_sales_status_scheduled_at` | flash_sales | PARTIAL | Sale activation scheduler |

---

## 7. Constraint Catalogue

### CHECK constraints

| Table | Constraint | Rule | Invariant Enforced |
|---|---|---|---|
| `flash_sales` | `flash_sales_status_ck` | status IN (4 valid values) | State machine validity |
| `flash_sales` | `flash_sales_total_stock_positive_ck` | total_stock > 0 | No zero-stock sales |
| `flash_sales` | `flash_sales_activated_at_ck` | activated_at NULL iff SCHEDULED | Status/timestamp consistency |
| `sale_schedules` | `sale_schedules_window_ck` | sale_end > sale_start | SaleWindow invariant |
| `products` | `products_total_stock_ck` | total_stock >= 0 | No negative global stock |
| `stock_levels` | `stock_levels_stock_ceiling_ck` | current_stock <= total_allocated | No over-allocation |
| `stock_levels` | `stock_levels_current_stock_ck` | current_stock >= 0 | StockCount invariant |
| `stock_levels` | `stock_levels_total_allocated_ck` | total_allocated > 0 | Meaningful allocation |
| `reservations` | `reservations_status_ck` | status IN (4 valid values) | State machine validity |
| `reservations` | `reservations_quantity_ck` | quantity >= 1 | Quantity invariant |
| `reservations` | `reservations_confirmed_at_ck` | confirmed_at populated iff CONFIRMED | Status/timestamp consistency |
| `orders` | `orders_status_ck` | status IN (4 valid values) | State machine validity |
| `orders` | `orders_amount_ck` | amount > 0 | Money invariant |
| `orders` | `orders_confirmed_at_ck` | confirmed_at populated iff CONFIRMED | Status/timestamp consistency |
| `idempotency_keys` | `idempotency_keys_http_status_ck` | http_status BETWEEN 100 AND 599 | Valid HTTP status |

### UNIQUE constraints

| Table | Constraint | Columns | Invariant Enforced |
|---|---|---|---|
| `products` | `products_sku_unique` | sku | No duplicate SKUs |
| `sale_schedules` | `sale_schedules_sale_id_unique` | sale_id | One schedule per sale |
| `stock_levels` | `stock_levels_product_sale_unique` | product_id, sale_id | One stock level per product per sale |
| `reservations` | `idx_reservations_user_sale_active` | user_id, sale_id (PARTIAL) | One active reservation per user per sale |
| `reservations` | `idx_reservations_idempotency_key` | idempotency_key | Idempotent reservation creation |
| `orders` | `idx_orders_idempotency_key` | idempotency_key | Core order idempotency |
| `orders` | `idx_orders_reservation_id` | reservation_id | One order per reservation |
| `order_outbox` | `order_outbox_event_id_unique` | event_id | Kafka deduplication |

---

## 8. Query Patterns

### sales_db query patterns

**QP-001 — Hot path: is this sale active?**
```sql
-- Redis-first; this query is only hit on cache miss or Redis failure
SELECT id, status, total_stock, version
FROM   flash_sales
WHERE  id = $1;
-- Plan: PK index scan, single row, < 1ms
```

**QP-002 — Scheduler: find sales to activate**
```sql
SELECT fs.id, fs.version, ss.sale_start
FROM   flash_sales  fs
JOIN   sale_schedules ss ON ss.sale_id = fs.id
WHERE  fs.status = 'SCHEDULED'
  AND  ss.sale_start <= NOW();
-- Plan: idx_flash_sales_status_scheduled_at → idx_sale_schedules_sale_id
-- Runs every 5 seconds by SaleService scheduler
```

**QP-003 — Scheduler: activate sale with optimistic lock**
```sql
UPDATE flash_sales
SET    status       = 'ACTIVE',
       activated_at = NOW(),
       version      = version + 1,
       updated_at   = NOW()
WHERE  id      = $1
  AND  status  = 'SCHEDULED'
  AND  version = $2;          -- optimistic lock check
-- Returns 0 rows if another pod already activated it — safe to ignore
```

**QP-004 — Admin: sale history**
```sql
SELECT from_status, to_status, transitioned_at, actor, reason
FROM   sale_status_history
WHERE  sale_id = $1
ORDER  BY transitioned_at DESC;
-- Plan: idx_sale_status_history_sale_id_time
```

---

### inventory_db query patterns

**QP-005 — Fallback: atomic stock decrement (Redis down)**
```sql
BEGIN;
    SELECT id, current_stock, version
    FROM   stock_levels
    WHERE  product_id = $1
      AND  sale_id    = $2
    FOR UPDATE;                -- row lock, prevents concurrent decrements

    -- Application checks current_stock > 0 before proceeding
    UPDATE stock_levels
    SET    current_stock = current_stock - $3,
           version       = version + 1,
           updated_at    = NOW()
    WHERE  id      = $4
      AND  version = $5;       -- optimistic check (belt-and-suspenders with FOR UPDATE)
COMMIT;
-- Plan: idx on UNIQUE(product_id, sale_id) → row lock
```

**QP-006 — Create reservation (with idempotency check)**
```sql
-- Step 1: check for existing active reservation (idempotency)
SELECT id, status, expires_at
FROM   reservations
WHERE  idempotency_key = $1;  -- idx_reservations_idempotency_key

-- Step 2: check active reservation limit (before insert)
SELECT id FROM reservations
WHERE  user_id = $1 AND sale_id = $2
  AND  status IN ('PENDING','CONFIRMED');
-- Plan: idx_reservations_user_sale_active (partial unique — fast)

-- Step 3: insert (will fail on unique violation if step 2 race)
INSERT INTO reservations (
    id, user_id, sale_id, product_id, status,
    quantity, expires_at, idempotency_key, created_at, updated_at
) VALUES (
    gen_random_uuid(), $1, $2, $3, 'PENDING',
    $4, $5, $6, NOW(), NOW()
);
```

**QP-007 — Expiry sweep: expire stale PENDING reservations**
```sql
-- Runs every 30 seconds in background scheduler
UPDATE reservations
SET    status     = 'EXPIRED',
       expired_at = NOW(),
       version    = version + 1,
       updated_at = NOW()
WHERE  status     = 'PENDING'
  AND  expires_at < NOW()
RETURNING id, product_id, sale_id, quantity;  -- triggers StockReleased events
-- Plan: idx_reservations_expiry_pending (partial index on PENDING rows only)
```

**QP-008 — Confirm reservation on order placement**
```sql
UPDATE reservations
SET    status       = 'CONFIRMED',
       confirmed_at = NOW(),
       order_id     = $1,
       version      = version + 1,
       updated_at   = NOW()
WHERE  id      = $2
  AND  status  = 'PENDING'
  AND  version = $3
  AND  expires_at > NOW();     -- reject if expired between reservation and order
-- Returns 0 rows if expired or already confirmed — saga compensation triggered
```

**QP-009 — Stock reconciliation (post-sale or Redis recovery)**
```sql
-- Compare Postgres stock to expected Redis value
SELECT sl.sale_id,
       sl.product_id,
       sl.current_stock          AS postgres_stock,
       sl.last_reconciled_at,
       COUNT(r.id) FILTER (WHERE r.status = 'CONFIRMED') AS confirmed_count,
       sl.total_allocated - COUNT(r.id) FILTER (WHERE r.status = 'CONFIRMED')
                                        AS expected_stock
FROM   stock_levels sl
LEFT   JOIN reservations r ON r.product_id = sl.product_id
                           AND r.sale_id    = sl.sale_id
WHERE  sl.sale_id = $1
GROUP  BY sl.id, sl.sale_id, sl.product_id, sl.current_stock,
          sl.total_allocated, sl.last_reconciled_at;
```

---

### orders_db query patterns

**QP-010 — Idempotency check (dual-layer path)**
```sql
-- Step 1: Redis check (application layer, not SQL)
-- Step 2: Postgres fallback on Redis miss
SELECT idempotency_key, response_payload, http_status, order_id
FROM   idempotency_keys
WHERE  idempotency_key = $1;  -- PK scan, < 1ms
```

**QP-011 — Place order (transactional — order + outbox in one transaction)**
```sql
BEGIN;
    -- Write the order
    INSERT INTO orders (
        id, user_id, sale_id, reservation_id, status,
        amount, currency, idempotency_key, version, created_at, updated_at
    ) VALUES (
        $1, $2, $3, $4, 'PENDING',
        $5, $6, $7, 0, NOW(), NOW()
    );

    -- Write the outbox event in the same transaction
    INSERT INTO order_outbox (
        id, order_id, event_id, event_type, event_version,
        aggregate_id, aggregate_type, payload, published, created_at
    ) VALUES (
        gen_random_uuid(), $1, gen_random_uuid(), 'OrderCreated', '1.0',
        $1, 'Order', $8::jsonb, FALSE, NOW()
    );

    -- Write the idempotency record
    INSERT INTO idempotency_keys (
        idempotency_key, response_payload, http_status, order_id, created_at
    ) VALUES (
        $7, $9, 202, $1, NOW()
    );
COMMIT;
-- If any statement fails, the entire transaction rolls back.
-- The outbox event is never written without the order. The order is never
-- written without the outbox event. Atomicity is guaranteed by Postgres.
```

**QP-012 — Outbox poller: fetch and publish batch**
```sql
-- Runs every 500ms, processes up to 100 rows
SELECT id, order_id, event_id, event_type, event_version,
       aggregate_id, aggregate_type, payload, attempt_count
FROM   order_outbox
WHERE  published = FALSE
ORDER  BY created_at ASC
LIMIT  100
FOR UPDATE SKIP LOCKED;        -- concurrent-safe across multiple OrderService pods
```

**QP-013 — Outbox poller: mark as published**
```sql
UPDATE order_outbox
SET    published        = TRUE,
       published_at     = NOW(),
       attempt_count    = attempt_count + 1,
       last_attempted_at = NOW()
WHERE  id = ANY($1::uuid[]);   -- batch update, array of IDs from QP-012
```

**QP-014 — Buyer: order history**
```sql
SELECT id, sale_id, status, amount, currency, created_at, confirmed_at
FROM   orders
WHERE  user_id     = $1
ORDER  BY created_at DESC
LIMIT  $2
OFFSET $3;
-- Plan: idx_orders_user_id_created_at — index-only scan if projecting covered columns
```

**QP-015 — Admin: orders for a sale**
```sql
SELECT id, user_id, status, amount, created_at
FROM   orders
WHERE  sale_id = $1
  AND  status  = $2           -- optional status filter
ORDER  BY created_at DESC;
-- Plan: idx_orders_sale_id_status
```

---

## 9. Design Decisions

### DD-001 — UUID PKs, not BIGSERIAL

All primary keys are `UUID` (generated via `gen_random_uuid()`). This enables:
- Distributed ID generation across service replicas with zero coordination
- IDs that are safe to expose in APIs without leaking sequence information
- Consistent ID type across all service boundaries

Trade-off: UUID PKs are 16 bytes vs 8 bytes for BIGINT. At flash sale scale (millions
of rows, not billions), this is negligible. The index size difference is acceptable.

### DD-002 — Partial indexes for state-machine tables

Tables that model state machines (`reservations`, `order_outbox`, `flash_sales`) have
large proportions of terminal rows (EXPIRED, RELEASED, published=TRUE). Partial indexes
on the active subset keep the working set small:

- `idx_order_outbox_unpublished` — shrinks toward zero under normal operation
- `idx_reservations_user_sale_active` — only PENDING and CONFIRMED rows
- `idx_flash_sales_status_scheduled_at` — only SCHEDULED rows

This is the difference between an index that stays at ~1k rows during a sale and one
that grows to ~50k rows per sale event.

### DD-003 — No cross-database FK constraints

Referential integrity across service boundaries is maintained through domain events
and saga compensation, not database constraints. The alternative — cross-database
FKs using `postgres_fdw` — would couple deployment pipelines and introduce a
distributed query execution path with no SLA.

### DD-004 — Optimistic locking on all aggregate roots

Every mutable aggregate root has a `version BIGINT` column. Updates always include
`AND version = $expected` in the WHERE clause and increment version on success.
A return of 0 rows means a concurrent update won — the caller retries.

This is belt-and-suspenders with `FOR UPDATE` on the stock fallback path. The Lua
script handles the hot path; optimistic locking handles the cold path.

### DD-005 — TIMESTAMPTZ everywhere

All timestamp columns are `TIMESTAMPTZ` (timestamp with time zone). `TIMESTAMP`
(without time zone) stores no timezone context — when the application server and
database server are in different timezones (common in Kubernetes), naive timestamps
produce silent, hard-to-diagnose errors. This is a zero-cost correctness improvement.

### DD-006 — Outbox payload as JSONB

The `order_outbox.payload` column is `JSONB`, not `TEXT`. This allows:
- Postgres-level JSON validation (malformed JSON is rejected at insert)
- Future: index on specific payload keys if query patterns require it
- Readability in psql and observability tooling

The `idempotency_keys.response_payload` is `TEXT` because it stores an already-
serialised HTTP response string — JSONB would re-parse it unnecessarily.

### DD-007 — Generated column for idempotency TTL

`idempotency_keys.expires_at` is a `GENERATED ALWAYS AS` column:
```sql
expires_at TIMESTAMPTZ GENERATED ALWAYS AS (created_at + INTERVAL '24 hours') STORED
```

This guarantees the 24-hour TTL contract from the domain model is enforced at the
database level, not just in application code. The constraint cannot be bypassed by
a bug in the application layer.

### DD-008 — FOR UPDATE SKIP LOCKED on outbox poller

The outbox poller uses `FOR UPDATE SKIP LOCKED` rather than `FOR UPDATE`. The
difference: `FOR UPDATE` blocks if another transaction holds a lock on the row.
`SKIP LOCKED` skips locked rows and picks the next available one. This allows
multiple OrderService pods to run the outbox poller concurrently without queuing —
each pod processes a non-overlapping batch of outbox rows.

---

*Schema derived from Final-Spec-Council.md v2.0 and DomainModel.md v1.0.*
*No cross-service FK constraints. No shared tables. No cross-database joins.*
*Next: Flyway migration scripts, JPA entity mappings, repository integration tests.*