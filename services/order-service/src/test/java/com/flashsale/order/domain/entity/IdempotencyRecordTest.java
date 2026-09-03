package com.flashsale.order.domain.entity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.order.domain.vo.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyRecordTest {

    private static final UserId USER_ID =
            UserId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    @Test
    void retainsOpaqueResponseForUserScopedKey() {
        IdempotencyRecord record = new IdempotencyRecord(
                USER_ID, "opaque-key", "{\"orderId\":\"123\"}", 202);

        assertEquals(USER_ID, record.userId());
        assertEquals("opaque-key", record.idempotencyKey());
        assertEquals("{\"orderId\":\"123\"}", record.responsePayload());
        assertEquals(202, record.httpStatus());
    }

    @Test
    void rejectsMissingIdentityOrPayload() {
        assertThrows(NullPointerException.class,
                () -> new IdempotencyRecord(null, "key", "{}", 202));
        assertThrows(NullPointerException.class,
                () -> new IdempotencyRecord(USER_ID, null, "{}", 202));
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyRecord(USER_ID, "  ", "{}", 202));
        assertThrows(NullPointerException.class,
                () -> new IdempotencyRecord(USER_ID, "key", null, 202));
    }

    @Test
    void validatesHttpStatusAndAllowsEmptyPayload() {
        assertDoesNotThrow(() -> new IdempotencyRecord(USER_ID, "key", "", 100));
        assertDoesNotThrow(() -> new IdempotencyRecord(USER_ID, "key", "", 599));
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyRecord(USER_ID, "key", "", 99));
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotencyRecord(USER_ID, "key", "", 600));
    }
}
