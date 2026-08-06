package com.flashsale.inventory.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identity for the {@code Reservation} aggregate.
 */
public record ReservationId(UUID value) {

    public ReservationId {
        Objects.requireNonNull(value, "ReservationId must not be null");
    }

    public static ReservationId generate() {
        return new ReservationId(UUID.randomUUID());
    }

    public static ReservationId of(String value) {
        return new ReservationId(UUID.fromString(value));
    }

    public static ReservationId of(UUID value) {
        return new ReservationId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
