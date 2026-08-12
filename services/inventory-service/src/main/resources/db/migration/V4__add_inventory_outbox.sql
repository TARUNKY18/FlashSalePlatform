CREATE TABLE inventory_outbox (
    id                UUID         NOT NULL,
    reservation_id    UUID         NOT NULL,
    event_id           UUID         NOT NULL,
    event_type         VARCHAR(100) NOT NULL,
    event_version      VARCHAR(10)  NOT NULL DEFAULT '1.0',
    aggregate_id       UUID         NOT NULL,
    aggregate_type     VARCHAR(50)  NOT NULL DEFAULT 'Reservation',
    payload            JSONB        NOT NULL,
    occurred_at        TIMESTAMPTZ  NOT NULL,
    published          BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at       TIMESTAMPTZ  NULL,
    attempt_count      INTEGER      NOT NULL DEFAULT 0,
    last_attempted_at  TIMESTAMPTZ  NULL,
    last_error         TEXT         NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT inventory_outbox_pkey PRIMARY KEY (id),
    CONSTRAINT inventory_outbox_reservation_id_fk
        FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE RESTRICT,
    CONSTRAINT inventory_outbox_event_id_unique UNIQUE (event_id)
);

CREATE INDEX idx_inventory_outbox_unpublished
    ON inventory_outbox (created_at ASC)
    WHERE published = FALSE;

CREATE INDEX idx_inventory_outbox_reservation_id
    ON inventory_outbox (reservation_id);
