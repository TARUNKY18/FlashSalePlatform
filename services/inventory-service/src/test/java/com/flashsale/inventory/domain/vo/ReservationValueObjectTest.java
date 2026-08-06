package com.flashsale.inventory.domain.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationValueObjectTest {

    private static final UUID VALUE = UUID.fromString("3b47e425-927e-46fa-9a61-e45a8f06314f");
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    // --- ReservationId ---

    @Test
    void reservationIdRejectsNull() {
        assertThrows(NullPointerException.class, () -> new ReservationId(null));
    }

    @Test
    void reservationIdOfStringAndUuid() {
        assertEquals(VALUE, ReservationId.of(VALUE).value());
        assertEquals(VALUE, ReservationId.of(VALUE.toString()).value());
    }

    @Test
    void reservationIdGenerateProducesUniqueValues() {
        assertNotEquals(ReservationId.generate(), ReservationId.generate());
    }

    @Test
    void reservationIdEqualsByValue() {
        assertEquals(ReservationId.of(VALUE), ReservationId.of(VALUE));
    }

    // --- UserId ---

    @Test
    void userIdRejectsNull() {
        assertThrows(NullPointerException.class, () -> new UserId(null));
    }

    @Test
    void userIdOfStringAndUuid() {
        assertEquals(VALUE, UserId.of(VALUE).value());
        assertEquals(VALUE, UserId.of(VALUE.toString()).value());
    }

    @Test
    void userIdIsDistinctFromSaleId() {
        assertNotEquals(UserId.of(VALUE), SaleId.of(VALUE));
    }

    // --- OrderId ---

    @Test
    void orderIdRejectsNull() {
        assertThrows(NullPointerException.class, () -> new OrderId(null));
    }

    @Test
    void orderIdOfStringAndUuid() {
        assertEquals(VALUE, OrderId.of(VALUE).value());
        assertEquals(VALUE, OrderId.of(VALUE.toString()).value());
    }

    @Test
    void orderIdIsDistinctFromUserId() {
        assertNotEquals(OrderId.of(VALUE), UserId.of(VALUE));
    }

    // --- Quantity ---

    @Test
    void quantityRejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(0));
    }

    @Test
    void quantityRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> Quantity.of(-1));
    }

    @Test
    void quantityOfAndOne() {
        assertEquals(1, Quantity.one().value());
        assertEquals(5, Quantity.of(5).value());
    }

    // --- ReservationExpiry ---

    @Test
    void reservationExpiryRejectsNullExpiresAt() {
        assertThrows(NullPointerException.class, () -> new ReservationExpiry(null));
    }

    @Test
    void reservationExpiryInFactoryRejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> ReservationExpiry.in(null, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> ReservationExpiry.in(Duration.ofMinutes(30), null));
    }

    @Test
    void reservationExpiryInComputesCorrectInstant() {
        Instant from     = Instant.parse("2099-06-01T10:00:00Z");
        Duration duration = Duration.ofMinutes(30);
        ReservationExpiry expiry = ReservationExpiry.in(duration, from);
        assertEquals(Instant.parse("2099-06-01T10:30:00Z"), expiry.expiresAt());
    }

    @Test
    void isExpiredReturnsFalseOneMillisecondBefore() {
        ReservationExpiry expiry = new ReservationExpiry(FUTURE);
        assertFalse(expiry.isExpired(FUTURE.minusMillis(1)));
    }

    @Test
    void isExpiredReturnsFalseAtExactExpiryMoment() {
        ReservationExpiry expiry = new ReservationExpiry(FUTURE);
        assertFalse(expiry.isExpired(FUTURE));
    }

    @Test
    void isExpiredReturnsTrueOneMillisecondAfter() {
        ReservationExpiry expiry = new ReservationExpiry(FUTURE);
        assertTrue(expiry.isExpired(FUTURE.plusMillis(1)));
    }

    @Test
    void remainingTtlIsPositiveBeforeExpiry() {
        ReservationExpiry expiry = new ReservationExpiry(FUTURE);
        assertTrue(expiry.remainingTtl(FUTURE.minusMillis(1000)).isPositive());
    }

    @Test
    void remainingTtlIsNegativeAfterExpiry() {
        ReservationExpiry expiry = new ReservationExpiry(FUTURE);
        assertTrue(expiry.remainingTtl(FUTURE.plusMillis(1000)).isNegative());
    }
}
