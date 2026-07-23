package com.flashsale.inventory.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed, opaque reference to a sale owned by SaleService.
 */
public record SaleId(UUID value) {

    public SaleId {
        Objects.requireNonNull(value, "SaleId must not be null");
    }

    public static SaleId of(String value) {
        return new SaleId(UUID.fromString(value));
    }

    public static SaleId of(UUID value) {
        return new SaleId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
