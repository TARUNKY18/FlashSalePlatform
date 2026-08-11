-- =============================================================================
-- Flash Sale Platform — inventory_db — V2__add_reservations.sql
-- Owner      : InventoryService
-- Aggregates : Reservation (root) + stock_reservation_log (append-only audit)
-- Scope      : Week 4 Slice 2 — Reservation persistence only
-- =============================================================================

-- ---------------------------------------------------------------------------
-- reservations
-- Maps to ReservationJpaEntity.
-- idempotency_key is nullable here; NOT NULL added in V3 when REST layer lands.
-- ---------------------------------------------------------------------------
CREATE TABLE reservations (
    id              UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    sale_id         UUID         NOT NULL,
    product_id      UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    quantity        INTEGER      NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    idempotency_key VARCHAR(255) NULL,
    order_id        UUID         NULL,
    version         BIGINT       NOT NULL,

    CONSTRAINT reservations_pkey
        PRIMARY KEY (id),
    CONSTRAINT reservations_product_id_fk
        FOREIGN KEY (product_id)
        REFERENCES products (id)
        ON DELETE RESTRICT,
    CONSTRAINT reservations_status_ck
        CHECK (status IN ('PENDING', 'CONFIRMED', 'EXPIRED', 'RELEASED')),
    CONSTRAINT reservations_quantity_ck
        CHECK (quantity >= 1),
    CONSTRAINT reservations_version_ck
        CHECK (version >= 0),
    CONSTRAINT reservations_idempotency_key_unique
        UNIQUE (idempotency_key)
);

-- One active reservation per user per sale; EXPIRED and RELEASED rows excluded.
CREATE UNIQUE INDEX idx_reservations_user_sale_active
    ON reservations (user_id, sale_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

-- Expiry sweep reads pending reservations by expires_at.
CREATE INDEX idx_reservations_expiry_pending
    ON reservations (expires_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- stock_reservation_log
-- Append-only audit table. No JPA entity this slice; write path added later.
-- ---------------------------------------------------------------------------
CREATE TABLE stock_reservation_log (
    id             UUID         NOT NULL,
    reservation_id UUID         NOT NULL,
    product_id     UUID         NOT NULL,
    sale_id        UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    operation      VARCHAR(20)  NOT NULL,
    quantity_delta INTEGER      NOT NULL,
    stock_before   INTEGER      NOT NULL,
    stock_after    INTEGER      NOT NULL,
    source         VARCHAR(20)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT stock_reservation_log_pkey
        PRIMARY KEY (id),
    CONSTRAINT stock_reservation_log_product_id_fk
        FOREIGN KEY (product_id)
        REFERENCES products (id)
        ON DELETE RESTRICT,
    CONSTRAINT stock_reservation_log_operation_ck
        CHECK (operation IN ('RESERVE', 'RELEASE', 'RECONCILE', 'EXPIRE')),
    CONSTRAINT stock_reservation_log_source_ck
        CHECK (source IN ('REDIS', 'POSTGRES_FALLBACK', 'RECONCILE'))
);
