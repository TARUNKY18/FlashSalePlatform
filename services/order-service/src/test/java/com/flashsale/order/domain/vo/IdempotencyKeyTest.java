package com.flashsale.order.domain.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    private static final String VALUE = "123e4567-e89b-42d3-a456-426614174000";
    private static final Instant CREATED_AT = Instant.parse("2099-01-01T00:00:00Z");

    @Test
    void acceptsUuidV4AndPreservesItsRepresentation() {
        IdempotencyKey key = IdempotencyKey.of(VALUE);

        assertEquals(VALUE, key.value());
    }

    @Test
    void rejectsNullBlankMalformedAndNonV4Values() {
        assertThrows(NullPointerException.class, () -> IdempotencyKey.of(null));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.of("  "));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.of("not-a-uuid"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKey.of("1-1-4000-8000-1"));
        assertThrows(IllegalArgumentException.class,
                () -> IdempotencyKey.of("123e4567-e89b-12d3-a456-426614174000"));
    }

    @Test
    void equalityAndSameRequestUseTheRecordValue() {
        IdempotencyKey key = IdempotencyKey.of(VALUE);
        IdempotencyKey same = IdempotencyKey.of(VALUE);
        IdempotencyKey different = IdempotencyKey.of(
                "123e4567-e89b-42d3-a456-426614174001");

        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());
        assertTrue(key.isSameRequest(same));
        assertNotEquals(key, different);
        assertFalse(key.isSameRequest(different));
        assertFalse(key.isSameRequest(null));
    }

    @Test
    void expiresExactlyTwentyFourHoursAfterCreation() {
        IdempotencyKey key = IdempotencyKey.of(VALUE);

        assertEquals(CREATED_AT.plusSeconds(24 * 60 * 60), key.expiresAt(CREATED_AT));
    }

    @Test
    void expiryUsesTheDocumentedBoundary() {
        IdempotencyKey key = IdempotencyKey.of(VALUE);

        assertFalse(key.isExpired(CREATED_AT, CREATED_AT.plusSeconds(23 * 60 * 60 + 59 * 60 + 59)));
        assertFalse(key.isExpired(CREATED_AT, CREATED_AT.plusSeconds(24 * 60 * 60)));
        assertTrue(key.isExpired(CREATED_AT, CREATED_AT.plusSeconds(24 * 60 * 60 + 1)));
    }

    @Test
    void expiryRejectsNullInstants() {
        IdempotencyKey key = IdempotencyKey.of(VALUE);

        assertThrows(NullPointerException.class, () -> key.expiresAt(null));
        assertThrows(NullPointerException.class, () -> key.isExpired(null, CREATED_AT));
        assertThrows(NullPointerException.class, () -> key.isExpired(CREATED_AT, null));
    }
}
