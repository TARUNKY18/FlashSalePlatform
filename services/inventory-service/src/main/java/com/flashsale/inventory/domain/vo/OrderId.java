package com.flashsale.inventory.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed, opaque reference to an order owned by OrderService.
 */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "OrderId must not be null");
    }

    public static OrderId of(String value) {
        return new OrderId(UUID.fromString(value));
    }

    public static OrderId of(UUID value) {
        return new OrderId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
