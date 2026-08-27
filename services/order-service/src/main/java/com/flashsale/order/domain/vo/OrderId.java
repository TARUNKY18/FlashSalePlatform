package com.flashsale.order.domain.vo;

import java.util.Objects;
import java.util.UUID;

/** Typed identity for the {@code Order} aggregate. */
public record OrderId(UUID value) {

    public OrderId {
        Objects.requireNonNull(value, "OrderId must not be null");
    }

    public static OrderId generate() {
        return new OrderId(UUID.randomUUID());
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
