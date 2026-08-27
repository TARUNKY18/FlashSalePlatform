package com.flashsale.order.domain.vo;

import java.util.Objects;
import java.util.UUID;

/** Typed, opaque reference to a user. */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "UserId must not be null");
    }

    public static UserId of(String value) {
        return new UserId(UUID.fromString(value));
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
