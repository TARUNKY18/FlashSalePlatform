package com.flashsale.inventory.domain.vo;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Expiry moment for a {@code Reservation}.
 */
public record ReservationExpiry(Instant expiresAt) {

    public ReservationExpiry {
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public static ReservationExpiry in(Duration duration, Instant from) {
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(from, "from must not be null");
        return new ReservationExpiry(from.plus(duration));
    }

    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return now.isAfter(expiresAt);
    }

    public Duration remainingTtl(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return Duration.between(now, expiresAt);
    }
}
