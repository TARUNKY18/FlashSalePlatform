package com.flashsale.order.domain.vo;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A UUID-v4 request identity with a fixed 24-hour expiry contract. */
public record IdempotencyKey(String value) {

    private static final Duration TTL = Duration.ofHours(24);

    public IdempotencyKey {
        Objects.requireNonNull(value, "IdempotencyKey must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey must not be blank");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("IdempotencyKey must be a valid UUID v4", exception);
        }
        if (uuid.version() != 4 || !uuid.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("IdempotencyKey must be a valid UUID v4");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    public boolean isSameRequest(IdempotencyKey other) {
        return equals(other);
    }

    public Instant expiresAt(Instant createdAt) {
        return Objects.requireNonNull(createdAt, "createdAt must not be null").plus(TTL);
    }

    public boolean isExpired(Instant createdAt, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return now.isAfter(expiresAt(createdAt));
    }
}
