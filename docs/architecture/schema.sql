-- =============================================================================
-- Flash Sale Platform — PostgreSQL Schema
-- Version     : 1.0
-- Date        : 2026-06-15
-- Sources     : Final-Spec-Council.md v2.0 · DomainModel.md v1.0
-- Databases   : sales_db · inventory_db · orders_db
-- Engine      : PostgreSQL 16+
-- Rules       :
--   • Zero cross-database foreign keys — each DB is owned by one service
--   • All PKs are UUID (gen_random_uuid()) — no sequential int PKs
--   • All timestamps are TIMESTAMPTZ — no naive timestamps anywhere
--   • Optimistic locking via version BIGINT on every mutable aggregate root
--   • Soft deletes via status column + ended/archived timestamps
--   • No ON DELETE CASCADE across service-boundary references (saleId, productId)
--     Those are opaque references, not FK constraints
-- =============================================================================


-- =============================================================================
-- sales_db
-- Owner   : SaleService
-- Aggregates : FlashSale (root) + SaleSchedule (entity)
-- =============================================================================

\connect sales_db

-- ---------------------------------------------------------------------------
-- flash_sales
-- Maps to : FlashSale aggregate root
-- Invariant: status transitions are append-only via sale_status_history
-- ---------------------------------------------------------------------------
CREATE TABLE flash_sales (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Identity
    name                VARCHAR(255) NOT NULL,
    product_id          UUID         NOT NULL,          -- opaque ref to inventory_db
    total_stock         INTEGER      NOT NULL,

    -- State machine (SaleStatus sealed interface)
    -- SCHEDULED | ACTIVE | ENDED | ARCHIVED
    status              VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED'
                            CONSTRAINT flash_sales_status_ck
                            CHECK (status IN ('SCHEDULED','ACTIVE','ENDED','ARCHIVED')),

    -- Timestamps per status transition (null until transition occurs)
    scheduled_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activated_at        TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    archived_at         TIMESTAMPTZ,
    end_reason          VARCHAR(50),                    -- TIME_ELAPSED | STOCK_DEPLETED | ADMIN_FORCE

    -- Optimistic lock — incremented on every status transition
    version             BIGINT       NOT NULL DEFAULT 0,

    -- Audit
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Hot path: GET /api/v1/sales/{id}/active — single row fetch by PK (PK index covers this)

-- Sale administrator: list all sales for a product
CREATE INDEX idx_flash_sales_product_id
    ON flash_sales (product_id);

-- Scheduler: find all SCHEDULED sales whose start time has arrived
CREATE INDEX idx_flash_sales_status_scheduled_at
    ON flash_sales (status, scheduled_at)
    WHERE status = 'SCHEDULED';

-- Scheduler: find all ACTIVE sales whose end time has passed
CREATE INDEX idx_flash_sales_status_ended_at
    ON flash_sales (status, ended_at)
    WHERE status = 'ACTIVE';

-- Admin: list sales by status (dashboard queries)
CREATE INDEX idx_flash_sales_status_created_at
    ON flash_sales (status, created_at DESC);

-- Constraint: total_stock must be positive
ALTER TABLE flash_sales
    ADD CONSTRAINT flash_sales_total_stock_positive_ck
    CHECK (total_stock > 0);

-- Constraint: activated_at only set when status leaves SCHEDULED
ALTER TABLE flash_sales
    ADD CONSTRAINT flash_sales_activated_at_ck
    CHECK (
        (status = 'SCHEDULED' AND activated_at IS NULL)
        OR (status IN ('ACTIVE','ENDED','ARCHIVED') AND activated_at IS NOT NULL)
    );

-- ---------------------------------------------------------------------------
-- sale_schedules
-- Maps to : SaleSchedule entity (owned by FlashSale aggregate)
-- Invariant: becomes immutable once parent sale transitions to ACTIVE
--            enforced at application layer via version check on flash_sales
-- ---------------------------------------------------------------------------
CREATE TABLE sale_schedules (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id             UUID        NOT NULL
                            REFERENCES flash_sales(id)
                            ON DELETE RESTRICT,         -- never delete a scheduled sale directly

    -- SaleWindow value object fields
    sale_start          TIMESTAMPTZ NOT NULL,
    sale_end            TIMESTAMPTZ NOT NULL,
    timezone            VARCHAR(64) NOT NULL DEFAULT 'UTC',

    -- Reschedule support (immutable once ACTIVE — enforced in app layer)
    version             BIGINT      NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT sale_schedules_window_ck CHECK (sale_end > sale_start),
    CONSTRAINT sale_schedules_sale_id_unique UNIQUE (sale_id)  -- one schedule per sale
);

-- Scheduler: find sales to activate — join path with flash_sales
CREATE INDEX idx_sale_schedules_sale_start
    ON sale_schedules (sale_start)
    WHERE sale_start > NOW() - INTERVAL '1 hour';    -- partial: only future/recent

-- Scheduler: find sales to end
CREATE INDEX idx_sale_schedules_sale_end
    ON sale_schedules (sale_end)
    WHERE sale_end > NOW() - INTERVAL '1 hour';

-- ---------------------------------------------------------------------------
-- sale_status_history
-- Purpose: immutable audit log for every status transition
--          Satisfies US-004 (admin audit trail)
-- ---------------------------------------------------------------------------
CREATE TABLE sale_status_history (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    sale_id             UUID        NOT NULL
                            REFERENCES flash_sales(id)
                            ON DELETE RESTRICT,

    from_status         VARCHAR(20),                   -- NULL for initial SCHEDULED entry
    to_status           VARCHAR(20) NOT NULL,
    transitioned_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor               VARCHAR(100) NOT NULL,          -- 'SCHEDULER' | 'ADMIN:{userId}'
    reason              VARCHAR(255),

    CONSTRAINT sale_status_history_to_status_ck
        CHECK (to_status IN ('SCHEDULED','ACTIVE','ENDED','ARCHIVED'))
);

-- Admin: GET /api/v1/sales/{id}/history ordered by time
CREATE INDEX idx_sale_status_history_sale_id_time
    ON sale_status_history (sale_id, transitioned_at DESC);


-- =============================================================================
-- inventory_db
-- Owner      : InventoryService
-- Aggregates : Product (root) + StockLevel (entity)
--              Reservation (separate root)
-- =============================================================================

\connect inventory_db

-- ---------------------------------------------------------------------------
-- products
-- Maps to : Product aggregate root
-- Invariant: total_stock >= sum of all ACTIVE StockLevel.current_stock
-- ---------------------------------------------------------------------------
CREATE TABLE products (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    name                VARCHAR(255) NOT NULL,
    sku                 VARCHAR(100) NOT NULL,
    description         TEXT,
    base_price          NUMERIC(12,2) NOT NULL,
    currency            CHAR(3)      NOT NULL DEFAULT 'USD',

    -- Global stock — across all sales
    total_stock         INTEGER      NOT NULL DEFAULT 0
                            CONSTRAINT products_total_stock_ck CHECK (total_stock >= 0),

    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version             BIGINT       NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT products_sku_unique UNIQUE (sku)
);

-- Lookup by SKU (admin, product catalog sync)
CREATE INDEX idx_products_sku ON products (sku);
-- Active product listing
CREATE INDEX idx_products_is_active ON products (is_active) WHERE is_active = TRUE;

-- ---------------------------------------------------------------------------
-- stock_levels
-- Maps to : StockLevel entity (owned by Product aggregate)
-- Invariant: current_stock >= 0, current_stock <= total_allocated
-- Hot path: SELECT FOR UPDATE fallback when Redis is down
--           Redis key: stock:{saleId} mirrors current_stock
-- ---------------------------------------------------------------------------
CREATE TABLE stock_levels (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID        NOT NULL
                            REFERENCES products(id)
                            ON DELETE RESTRICT,
    sale_id             UUID        NOT NULL,           -- opaque ref to sales_db — no FK

    total_allocated     INTEGER     NOT NULL
                            CONSTRAINT stock_levels_total_allocated_ck CHECK (total_allocated > 0),
    current_stock       INTEGER     NOT NULL
                            CONSTRAINT stock_levels_current_stock_ck   CHECK (current_stock >= 0),

    -- Ensure current never exceeds allocated
    CONSTRAINT stock_levels_stock_ceiling_ck
        CHECK (current_stock <= total_allocated),

    -- Redis sync metadata
    redis_warmed_at     TIMESTAMPTZ,                   -- last time Redis was pre-warmed
    last_reconciled_at  TIMESTAMPTZ,                   -- last Redis↔Postgres reconciliation

    -- Optimistic lock — used during Postgres fallback (SELECT FOR UPDATE path)
    version             BIGINT      NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- One StockLevel per product per sale
    CONSTRAINT stock_levels_product_sale_unique UNIQUE (product_id, sale_id)
);

-- CRITICAL: Fallback read path — InventoryService SELECT FOR UPDATE
-- "SELECT * FROM stock_levels WHERE product_id=? AND sale_id=? FOR UPDATE"
-- Covered by the UNIQUE constraint index above — no additional index needed

-- Reconciliation: find all stock levels for a sale (post-sale audit)
CREATE INDEX idx_stock_levels_sale_id
    ON stock_levels (sale_id);

-- Product stock overview (admin dashboard)
CREATE INDEX idx_stock_levels_product_id
    ON stock_levels (product_id);

-- ---------------------------------------------------------------------------
-- reservations
-- Maps to : Reservation aggregate root (separate from Product)
-- Invariant: one PENDING/CONFIRMED reservation per (user_id, sale_id)
-- State    : PENDING | CONFIRMED | EXPIRED | RELEASED
-- ---------------------------------------------------------------------------
CREATE TABLE reservations (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- References (opaque — no cross-DB FKs)
    user_id             UUID        NOT NULL,
    sale_id             UUID        NOT NULL,
    product_id          UUID        NOT NULL
                            REFERENCES products(id)
                            ON DELETE RESTRICT,

    -- Reservation state machine
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CONSTRAINT reservations_status_ck
                            CHECK (status IN ('PENDING','CONFIRMED','EXPIRED','RELEASED')),

    -- Quantity value object
    quantity            INTEGER     NOT NULL DEFAULT 1
                            CONSTRAINT reservations_quantity_ck CHECK (quantity >= 1),

    -- ReservationExpiry value object
    expires_at          TIMESTAMPTZ NOT NULL,

    -- Idempotency: client-supplied key prevents duplicate reservations on retry
    idempotency_key     VARCHAR(255) NOT NULL,

    -- Transition timestamps
    confirmed_at        TIMESTAMPTZ,
    expired_at          TIMESTAMPTZ,
    released_at         TIMESTAMPTZ,
    release_reason      VARCHAR(50),                   -- SAGA_COMPENSATION | USER_CANCEL | TIMEOUT

    -- Link to confirming order (set on CONFIRMED transition)
    order_id            UUID,                          -- opaque ref to orders_db

    -- Optimistic lock
    version             BIGINT      NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- CRITICAL invariant: one active reservation per user per sale
-- Partial unique index — only PENDING and CONFIRMED count as "active"
CREATE UNIQUE INDEX idx_reservations_user_sale_active
    ON reservations (user_id, sale_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

-- Idempotency: duplicate request detection
CREATE UNIQUE INDEX idx_reservations_idempotency_key
    ON reservations (idempotency_key);

-- Expiry sweep: scheduler finds PENDING reservations past their TTL
CREATE INDEX idx_reservations_expiry_pending
    ON reservations (expires_at)
    WHERE status = 'PENDING';

-- OrderService ACL consumer: find reservation by id + validate status
-- (PK index covers this — no additional index needed)

-- Lookup reservations for a sale (admin monitoring, reconciliation)
CREATE INDEX idx_reservations_sale_id_status
    ON reservations (sale_id, status);

-- Lookup reservations for a user (buyer history API)
CREATE INDEX idx_reservations_user_id_created_at
    ON reservations (user_id, created_at DESC);

-- Constraint: confirmed_at only populated on CONFIRMED
ALTER TABLE reservations
    ADD CONSTRAINT reservations_confirmed_at_ck
    CHECK (
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
        OR (status != 'CONFIRMED' AND confirmed_at IS NULL)
    );

-- ---------------------------------------------------------------------------
-- stock_reservation_log
-- Purpose : Append-only audit of every stock mutation
--           Used for Redis↔Postgres reconciliation and fraud detection
--           NOT used for stock reads — that is always stock_levels
-- ---------------------------------------------------------------------------
CREATE TABLE stock_reservation_log (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id  UUID        NOT NULL,              -- opaque ref
    product_id      UUID        NOT NULL
                        REFERENCES products(id)
                        ON DELETE RESTRICT,
    sale_id         UUID        NOT NULL,
    user_id         UUID        NOT NULL,

    operation       VARCHAR(20) NOT NULL
                        CONSTRAINT stock_log_operation_ck
                        CHECK (operation IN ('RESERVE','RELEASE','RECONCILE','EXPIRE')),
    quantity_delta  INTEGER     NOT NULL,               -- negative = decrement
    stock_before    INTEGER     NOT NULL,
    stock_after     INTEGER     NOT NULL,
    source          VARCHAR(20) NOT NULL DEFAULT 'REDIS'
                        CONSTRAINT stock_log_source_ck
                        CHECK (source IN ('REDIS','POSTGRES_FALLBACK','RECONCILE')),

    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Reconciliation: replay log for a sale
CREATE INDEX idx_stock_log_sale_product
    ON stock_reservation_log (sale_id, product_id, occurred_at DESC);

-- Fraud/audit: all operations for a reservation
CREATE INDEX idx_stock_log_reservation_id
    ON stock_reservation_log (reservation_id);


-- =============================================================================
-- orders_db
-- Owner      : OrderService
-- Aggregates : Order (root) with OutboxEvent + IdempotencyRecord (entities)
-- =============================================================================

\connect orders_db

-- ---------------------------------------------------------------------------
-- orders
-- Maps to : Order aggregate root
-- Invariant: one order per idempotency_key (enforced by UNIQUE constraint)
-- State    : PENDING | CONFIRMED | CANCELLED | EXPIRED
-- ---------------------------------------------------------------------------
CREATE TABLE orders (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- References (opaque — no cross-DB FKs)
    user_id             UUID        NOT NULL,
    sale_id             UUID        NOT NULL,
    reservation_id      UUID        NOT NULL,          -- opaque ref to inventory_db

    -- State machine
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CONSTRAINT orders_status_ck
                            CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED')),

    -- Money value object
    amount              NUMERIC(12,2) NOT NULL
                            CONSTRAINT orders_amount_ck CHECK (amount > 0),
    currency            CHAR(3)       NOT NULL DEFAULT 'USD',

    -- IdempotencyKey value object — the core uniqueness guarantee
    idempotency_key     VARCHAR(255)  NOT NULL,

    -- Transition timestamps
    confirmed_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    expired_at          TIMESTAMPTZ,
    cancel_reason       VARCHAR(100),                  -- PAYMENT_FAILED | SAGA_COMPENSATION | TIMEOUT

    -- Optimistic lock
    version             BIGINT        NOT NULL DEFAULT 0,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- CRITICAL: Core idempotency guarantee — the entire correctness of OrderService
-- depends on this constraint. One order per idempotency_key, forever.
CREATE UNIQUE INDEX idx_orders_idempotency_key
    ON orders (idempotency_key);

-- CRITICAL: One order per reservation — a reservation can only be consumed once
CREATE UNIQUE INDEX idx_orders_reservation_id
    ON orders (reservation_id);

-- User order history (buyer-facing API)
CREATE INDEX idx_orders_user_id_created_at
    ON orders (user_id, created_at DESC);

-- Sale-level order summary (admin dashboard, analytics reconciliation)
CREATE INDEX idx_orders_sale_id_status
    ON orders (sale_id, status);

-- Outbox poller: find orders whose outbox events are unpublished
-- (covered by order_outbox index — no additional index on orders needed)

-- Constraint: confirmed_at only populated on CONFIRMED
ALTER TABLE orders
    ADD CONSTRAINT orders_confirmed_at_ck
    CHECK (
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
        OR (status != 'CONFIRMED' AND confirmed_at IS NULL)
    );

-- ---------------------------------------------------------------------------
-- order_outbox
-- Maps to : OutboxEvent entity (owned by Order aggregate)
-- Invariant: written in same DB transaction as the parent order row
--            published = FALSE until outbox poller processes it
-- Pattern  : Transactional Outbox — guarantees at-least-once Kafka delivery
-- ---------------------------------------------------------------------------
CREATE TABLE order_outbox (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID        NOT NULL
                        REFERENCES orders(id)
                        ON DELETE RESTRICT,

    -- DomainEvent envelope fields
    event_id        UUID        NOT NULL DEFAULT gen_random_uuid(),  -- Kafka dedup key
    event_type      VARCHAR(100) NOT NULL,                -- 'OrderCreated' | 'OrderCancelled' etc.
    event_version   VARCHAR(10)  NOT NULL DEFAULT '1.0',
    aggregate_id    UUID        NOT NULL,
    aggregate_type  VARCHAR(50) NOT NULL DEFAULT 'Order',
    payload         JSONB       NOT NULL,

    -- Publish state
    published       BOOLEAN     NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ,

    -- Retry tracking
    attempt_count   SMALLINT    NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMPTZ,
    last_error      TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Each event_id must be unique (Kafka deduplication key)
    CONSTRAINT order_outbox_event_id_unique UNIQUE (event_id)
);

-- CRITICAL: Outbox poller hot path
-- "SELECT * FROM order_outbox WHERE published = FALSE ORDER BY created_at LIMIT 100"
-- Partial index on unpublished rows only — this is the most-read index in orders_db
CREATE INDEX idx_order_outbox_unpublished
    ON order_outbox (created_at ASC)
    WHERE published = FALSE;

-- Audit: all outbox events for a given order
CREATE INDEX idx_order_outbox_order_id
    ON order_outbox (order_id);

-- Idempotent consumer deduplication lookup by event_id
-- (Covered by UNIQUE constraint index above)

-- ---------------------------------------------------------------------------
-- idempotency_keys
-- Maps to : IdempotencyRecord entity (owned by Order aggregate)
-- Purpose : Durable fallback for the Redis idempotency cache (Layer 3)
--           Redis TTL is 24h; this table is the permanent record
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    -- The key IS the identity — no surrogate PK needed
    idempotency_key     VARCHAR(255)  PRIMARY KEY,

    -- Stored response (serialised HTTP response)
    response_payload    TEXT          NOT NULL,         -- JSON-serialised response body
    http_status         SMALLINT      NOT NULL,         -- 200 | 202 | 409 | 422 etc.

    -- Link back to the order created (may be NULL if order creation failed)
    order_id            UUID
                            REFERENCES orders(id)
                            ON DELETE RESTRICT,

    -- TTL metadata (Redis uses this to set TTL; app uses for expiry check)
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ   NOT NULL           -- DEFAULT: created_at + 24h
                            GENERATED ALWAYS AS (created_at + INTERVAL '24 hours') STORED,

    CONSTRAINT idempotency_keys_http_status_ck
        CHECK (http_status BETWEEN 100 AND 599)
);

-- Cache re-warm: look up by key (PK covers this)

-- Cleanup job: delete expired keys (run nightly)
CREATE INDEX idx_idempotency_keys_expires_at
    ON idempotency_keys (expires_at)
    WHERE expires_at < NOW();

-- Reverse lookup: which idempotency key belongs to this order?
CREATE INDEX idx_idempotency_keys_order_id
    ON idempotency_keys (order_id)
    WHERE order_id IS NOT NULL;


-- =============================================================================
-- Shared: updated_at trigger function
-- Apply to every table with an updated_at column
-- =============================================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

-- sales_db triggers
CREATE TRIGGER trg_flash_sales_updated_at
    BEFORE UPDATE ON flash_sales
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_sale_schedules_updated_at
    BEFORE UPDATE ON sale_schedules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- inventory_db triggers
CREATE TRIGGER trg_products_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_stock_levels_updated_at
    BEFORE UPDATE ON stock_levels
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_reservations_updated_at
    BEFORE UPDATE ON reservations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- orders_db triggers
CREATE TRIGGER trg_orders_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();