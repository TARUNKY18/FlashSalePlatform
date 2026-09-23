CREATE TABLE orders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    sale_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,
    cancel_reason VARCHAR(100),
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT orders_status_ck
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT orders_amount_ck CHECK (amount > 0),
    CONSTRAINT orders_confirmed_at_ck CHECK (
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL)
        OR (status <> 'CONFIRMED' AND confirmed_at IS NULL)
    )
);

CREATE UNIQUE INDEX idx_orders_user_id_idempotency_key
    ON orders (user_id, idempotency_key);

CREATE UNIQUE INDEX idx_orders_reservation_id
    ON orders (reservation_id);

CREATE TABLE order_outbox (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version VARCHAR(10) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT order_outbox_order_fk
        FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT,
    CONSTRAINT order_outbox_event_id_unique UNIQUE (event_id)
);

CREATE INDEX idx_order_outbox_order_id ON order_outbox (order_id);
