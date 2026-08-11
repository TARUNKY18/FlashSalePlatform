-- V3: enforce idempotency_key NOT NULL now that the REST layer supplies it on every insert.
ALTER TABLE reservations ALTER COLUMN idempotency_key SET NOT NULL;
