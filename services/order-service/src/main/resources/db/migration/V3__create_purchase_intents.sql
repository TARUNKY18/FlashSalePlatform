-- Task 6.3: purchase intents received from inventory-events StockReserved.
-- purchase_intent_id is the upstream reservationId; first delivery wins.
CREATE TABLE purchase_intents (
    purchase_intent_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    sale_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT purchase_intents_quantity_ck CHECK (quantity > 0)
);
