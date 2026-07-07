-- =============================================================================
-- Flash Sale Platform — sales_db — V1__init.sql
-- Owner   : SaleService
-- Source  : schema.sql (sales_db section, lines 19-154) — reproduced verbatim,
--           minus the `\connect sales_db` psql meta-command, which has no meaning
--           to Flyway (the JDBC connection is already scoped to sales_db).
-- Aggregates : FlashSale (root) + SaleSchedule (entity)
-- Rules      :
--   • All PKs are UUID (gen_random_uuid()) — no sequential int PKs
--   • All timestamps are TIMESTAMPTZ — no naive timestamps anywhere
--   • Optimistic locking via version BIGINT on every mutable aggregate root
--   • No ON DELETE CASCADE across service-boundary references (saleId, productId)
--     Those are opaque references, not FK constraints
-- =============================================================================

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
-- Shared: updated_at trigger function
-- Source: schema.sql "Shared: updated_at trigger function" section.
-- Only the sales_db-owned triggers are created here; inventory_db/orders_db define
-- their own copy of this function in their own Flyway histories (no cross-database
-- function sharing — each service's migration history is self-contained).
-- =============================================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_flash_sales_updated_at
    BEFORE UPDATE ON flash_sales
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_sale_schedules_updated_at
    BEFORE UPDATE ON sale_schedules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
