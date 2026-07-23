package com.flashsale.inventory.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identity for a {@code StockLevel} entity.
 */
public record StockLevelId(UUID value) {

    public StockLevelId {
        Objects.requireNonNull(value, "StockLevelId must not be null");
    }

    public static StockLevelId generate() {
        return new StockLevelId(UUID.randomUUID());
    }

    public static StockLevelId of(String value) {
        return new StockLevelId(UUID.fromString(value));
    }

    public static StockLevelId of(UUID value) {
        return new StockLevelId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
