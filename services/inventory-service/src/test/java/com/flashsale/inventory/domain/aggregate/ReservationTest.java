package com.flashsale.inventory.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.inventory.domain.vo.OrderId;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.ReservationId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationTest {

    private static final Instant NOW       = Instant.parse("2099-01-01T12:00:00Z");
    private static final Instant PAST      = NOW.minusSeconds(3600);
    private static final ReservationExpiry VALID_EXPIRY =
            ReservationExpiry.in(Duration.ofMinutes(30), NOW);

    private static final UserId    USER_ID    = UserId.of(UUID.randomUUID());
    private static final SaleId    SALE_ID    = SaleId.of(UUID.randomUUID());
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.randomUUID());
    private static final Quantity  QTY        = Quantity.one();
    private static final OrderId   ORDER_ID   = OrderId.of(UUID.randomUUID());

    private Reservation pending() {
        return Reservation.create(USER_ID, SALE_ID, PRODUCT_ID, QTY, VALID_EXPIRY, NOW);
    }

    // --- create ---

    @Test
    void createBuildsPendingReservationWithCorrectFields() {
        Reservation r = pending();

        assertNotNull(r.id());
        assertEquals(USER_ID,     r.userId());
        assertEquals(SALE_ID,     r.saleId());
        assertEquals(PRODUCT_ID,  r.productId());
        assertEquals(QTY,         r.quantity());
        assertEquals(VALID_EXPIRY, r.expiry());
        assertInstanceOf(Reservation.Status.Pending.class, r.status());
        assertNull(r.orderId());
        assertEquals(0L, r.version());
    }

    @Test
    void createRejectsNullUserId() {
        assertThrows(NullPointerException.class,
                () -> Reservation.create(null, SALE_ID, PRODUCT_ID, QTY, VALID_EXPIRY, NOW));
    }

    @Test
    void createRejectsNullSaleId() {
        assertThrows(NullPointerException.class,
                () -> Reservation.create(USER_ID, null, PRODUCT_ID, QTY, VALID_EXPIRY, NOW));
    }

    @Test
    void createRejectsNullProductId() {
        assertThrows(NullPointerException.class,
                () -> Reservation.create(USER_ID, SALE_ID, null, QTY, VALID_EXPIRY, NOW));
    }

    @Test
    void createRejectsNullQuantity() {
        assertThrows(NullPointerException.class,
                () -> Reservation.create(USER_ID, SALE_ID, PRODUCT_ID, null, VALID_EXPIRY, NOW));
    }

    @Test
    void createRejectsNullExpiry() {
        assertThrows(NullPointerException.class,
                () -> Reservation.create(USER_ID, SALE_ID, PRODUCT_ID, QTY, null, NOW));
    }

    @Test
    void createRejectsAlreadyExpiredExpiry() {
        ReservationExpiry alreadyExpired = new ReservationExpiry(PAST);
        assertThrows(IllegalArgumentException.class,
                () -> Reservation.create(USER_ID, SALE_ID, PRODUCT_ID, QTY, alreadyExpired, NOW));
    }

    // --- confirm ---

    @Test
    void confirmTransitionsPendingToConfirmedAndAdvancesVersion() {
        Reservation r = pending();
        r.confirm(ORDER_ID);

        assertInstanceOf(Reservation.Status.Confirmed.class, r.status());
        assertEquals(ORDER_ID, r.orderId());
        assertEquals(1L, r.version());
    }

    @Test
    void confirmRejectsNullOrderId() {
        assertThrows(NullPointerException.class, () -> pending().confirm(null));
    }

    @Test
    void confirmThrowsFromConfirmed() {
        Reservation r = pending();
        r.confirm(ORDER_ID);
        assertThrows(IllegalStateException.class, () -> r.confirm(ORDER_ID));
    }

    @Test
    void confirmThrowsFromExpired() {
        Reservation r = pending();
        r.expire();
        assertThrows(IllegalStateException.class, () -> r.confirm(ORDER_ID));
    }

    @Test
    void confirmThrowsFromReleased() {
        Reservation r = pending();
        r.release("USER_CANCEL");
        assertThrows(IllegalStateException.class, () -> r.confirm(ORDER_ID));
    }

    // --- expire ---

    @Test
    void expireTransitionsPendingToExpiredAndAdvancesVersion() {
        Reservation r = pending();
        r.expire();

        assertInstanceOf(Reservation.Status.Expired.class, r.status());
        assertEquals(1L, r.version());
    }

    @Test
    void expireThrowsFromConfirmed() {
        Reservation r = pending();
        r.confirm(ORDER_ID);
        assertThrows(IllegalStateException.class, r::expire);
    }

    @Test
    void expireThrowsFromExpired() {
        Reservation r = pending();
        r.expire();
        assertThrows(IllegalStateException.class, r::expire);
    }

    @Test
    void expireThrowsFromReleased() {
        Reservation r = pending();
        r.release("TIMEOUT");
        assertThrows(IllegalStateException.class, r::expire);
    }

    // --- release ---

    @Test
    void releaseTransitionsPendingToReleasedAndAdvancesVersion() {
        Reservation r = pending();
        r.release("SAGA_COMPENSATION");

        assertInstanceOf(Reservation.Status.Released.class, r.status());
        assertEquals(1L, r.version());
    }

    @Test
    void releaseRejectsNullReason() {
        assertThrows(NullPointerException.class, () -> pending().release(null));
    }

    @Test
    void releaseThrowsFromConfirmed() {
        Reservation r = pending();
        r.confirm(ORDER_ID);
        assertThrows(IllegalStateException.class, () -> r.release("SAGA_COMPENSATION"));
    }

    @Test
    void releaseThrowsFromExpired() {
        Reservation r = pending();
        r.expire();
        assertThrows(IllegalStateException.class, () -> r.release("SAGA_COMPENSATION"));
    }

    @Test
    void releaseThrowsFromReleased() {
        Reservation r = pending();
        r.release("USER_CANCEL");
        assertThrows(IllegalStateException.class, () -> r.release("USER_CANCEL"));
    }

    // --- version isolation ---

    @Test
    void versionDoesNotAdvanceOnRejectedCommand() {
        Reservation r = pending();
        r.confirm(ORDER_ID);
        long versionAfterConfirm = r.version();

        assertThrows(IllegalStateException.class, () -> r.confirm(ORDER_ID));
        assertEquals(versionAfterConfirm, r.version());
    }

    @Test
    void initialVersionIsZero() {
        assertEquals(0L, pending().version());
    }

    // --- reconstitute ---

    @Test
    void reconstituteRebuildsAggregateWithCorrectFields() {
        ReservationId id = ReservationId.generate();
        Reservation r = Reservation.reconstitute(
                id, USER_ID, SALE_ID, PRODUCT_ID, QTY, VALID_EXPIRY,
                new Reservation.Status.Confirmed(), ORDER_ID, 5L
        );

        assertEquals(id,           r.id());
        assertEquals(USER_ID,      r.userId());
        assertEquals(SALE_ID,      r.saleId());
        assertEquals(PRODUCT_ID,   r.productId());
        assertEquals(QTY,          r.quantity());
        assertEquals(VALID_EXPIRY, r.expiry());
        assertInstanceOf(Reservation.Status.Confirmed.class, r.status());
        assertEquals(ORDER_ID,     r.orderId());
        assertEquals(5L,           r.version());
    }

    @Test
    void reconstituteRejectsNullId() {
        assertThrows(NullPointerException.class, () ->
                Reservation.reconstitute(null, USER_ID, SALE_ID, PRODUCT_ID, QTY,
                        VALID_EXPIRY, new Reservation.Status.Pending(), null, 0L));
    }

    @Test
    void reconstituteRejectsNegativeVersion() {
        assertThrows(IllegalArgumentException.class, () ->
                Reservation.reconstitute(ReservationId.generate(), USER_ID, SALE_ID,
                        PRODUCT_ID, QTY, VALID_EXPIRY,
                        new Reservation.Status.Pending(), null, -1L));
    }
}
