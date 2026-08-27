package com.flashsale.order.domain.vo;

import java.util.Objects;
import java.util.UUID;

/** Order-owned reference to an Inventory purchase intent. */
public record PurchaseIntentId(UUID value) {

    public PurchaseIntentId {
        Objects.requireNonNull(value, "PurchaseIntentId must not be null");
    }

    public static PurchaseIntentId of(String value) {
        return new PurchaseIntentId(UUID.fromString(value));
    }

    public static PurchaseIntentId of(UUID value) {
        return new PurchaseIntentId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
