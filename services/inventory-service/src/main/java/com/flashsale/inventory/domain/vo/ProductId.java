package com.flashsale.inventory.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identity for the {@code Product} aggregate.
 */
public record ProductId(UUID value) {

    public ProductId {
        Objects.requireNonNull(value, "ProductId must not be null");
    }

    public static ProductId generate() {
        return new ProductId(UUID.randomUUID());
    }

    public static ProductId of(String value) {
        return new ProductId(UUID.fromString(value));
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
