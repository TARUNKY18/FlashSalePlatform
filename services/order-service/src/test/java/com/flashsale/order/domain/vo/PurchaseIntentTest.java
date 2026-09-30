package com.flashsale.order.domain.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseIntentTest {

    private static final PurchaseIntentId ID = PurchaseIntentId.of(UUID.randomUUID());
    private static final UserId USER = UserId.of(UUID.randomUUID());
    private static final SaleId SALE = SaleId.of(UUID.randomUUID());
    private static final Instant VALID_UNTIL = Instant.parse("2099-01-01T00:10:00Z");

    @Test
    void exposesAllFields() {
        PurchaseIntent intent = new PurchaseIntent(ID, USER, SALE, 2, VALID_UNTIL);

        assertEquals(ID, intent.purchaseIntentId());
        assertEquals(USER, intent.userId());
        assertEquals(SALE, intent.saleId());
        assertEquals(2, intent.quantity());
        assertEquals(VALID_UNTIL, intent.validUntil());
    }

    @Test
    void rejectsNullReferences() {
        assertThrows(NullPointerException.class, () -> new PurchaseIntent(null, USER, SALE, 1, VALID_UNTIL));
        assertThrows(NullPointerException.class, () -> new PurchaseIntent(ID, null, SALE, 1, VALID_UNTIL));
        assertThrows(NullPointerException.class, () -> new PurchaseIntent(ID, USER, null, 1, VALID_UNTIL));
        assertThrows(NullPointerException.class, () -> new PurchaseIntent(ID, USER, SALE, 1, null));
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new PurchaseIntent(ID, USER, SALE, 0, VALID_UNTIL));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseIntent(ID, USER, SALE, -1, VALID_UNTIL));
    }

    @Test
    void isValidOnlyStrictlyBeforeValidUntil() {
        PurchaseIntent intent = new PurchaseIntent(ID, USER, SALE, 1, VALID_UNTIL);

        assertTrue(intent.isStillValid(VALID_UNTIL.minusNanos(1)));
        assertFalse(intent.isStillValid(VALID_UNTIL));
        assertFalse(intent.isStillValid(VALID_UNTIL.plusNanos(1)));
        assertThrows(NullPointerException.class, () -> intent.isStillValid(null));
    }
}
