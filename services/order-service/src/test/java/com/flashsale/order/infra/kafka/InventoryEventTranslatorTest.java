package com.flashsale.order.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.domain.vo.PurchaseIntent;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InventoryEventTranslatorTest {

    private static final String RESERVATION = "11111111-1111-4111-8111-111111111111";
    private static final String SALE = "22222222-2222-4222-8222-222222222222";
    private static final String PRODUCT = "33333333-3333-4333-8333-333333333333";
    private static final String USER = "44444444-4444-4444-8444-444444444444";
    private static final String EXPIRES_AT = "2026-06-15T12:10:00Z";

    private final InventoryEventTranslator translator = new InventoryEventTranslator();

    @Test
    void mapsWirePayloadToPurchaseIntent() {
        PurchaseIntent intent = translator.translate(payload(RESERVATION, SALE, USER, 3, EXPIRES_AT));

        assertEquals(PurchaseIntentId.of(RESERVATION), intent.purchaseIntentId());
        assertEquals(SaleId.of(SALE), intent.saleId());
        assertEquals(UserId.of(USER), intent.userId());
        assertEquals(3, intent.quantity());
        assertEquals(Instant.parse(EXPIRES_AT), intent.validUntil());
    }

    @Test
    void deserializesInventoryShapedJsonIgnoringUnknownFields() throws Exception {
        String envelope = """
                {"eventId":"55555555-5555-4555-8555-555555555555","eventType":"StockReserved",
                 "eventVersion":"1.0","occurredAt":"2026-06-15T12:00:00Z",
                 "aggregateId":"%1$s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%1$s","saleId":"%2$s","productId":"%3$s",
                            "userId":"%4$s","quantity":1,"remainingStock":142,
                            "expiresAt":"%5$s","sourceOfTruth":"REDIS"}}
                """.formatted(RESERVATION, SALE, PRODUCT, USER, EXPIRES_AT);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(envelope);

        StockReservedPayload payload = mapper.treeToValue(node.get("payload"), StockReservedPayload.class);
        PurchaseIntent intent = translator.translate(payload);

        assertEquals(PRODUCT, payload.productId());
        assertEquals(142, payload.remainingStock());
        assertEquals(PurchaseIntentId.of(RESERVATION), intent.purchaseIntentId());
        assertEquals(1, intent.quantity());
        assertEquals(Instant.parse(EXPIRES_AT), intent.validUntil());
    }

    @Test
    void translatesAlreadyExpiredReservation() {
        PurchaseIntent intent = translator.translate(payload(RESERVATION, SALE, USER, 1, "2000-01-01T00:00:00Z"));

        assertEquals(Instant.parse("2000-01-01T00:00:00Z"), intent.validUntil());
    }

    @Test
    void rejectsInvalidInputWithIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> translator.translate(null));
        assertRejected(payload(null, SALE, USER, 1, EXPIRES_AT));
        assertRejected(payload(" ", SALE, USER, 1, EXPIRES_AT));
        assertRejected(payload(RESERVATION, null, USER, 1, EXPIRES_AT));
        assertRejected(payload(RESERVATION, "", USER, 1, EXPIRES_AT));
        assertRejected(payload(RESERVATION, SALE, null, 1, EXPIRES_AT));
        assertRejected(payload(RESERVATION, SALE, " ", 1, EXPIRES_AT));
        assertRejected(payload(RESERVATION, SALE, USER, 1, null));
        assertRejected(payload(RESERVATION, SALE, USER, 1, ""));
        assertRejected(payload("not-a-uuid", SALE, USER, 1, EXPIRES_AT));
        assertRejected(payload(RESERVATION, SALE, USER, 1, "tomorrow"));
        assertRejected(payload(RESERVATION, SALE, USER, 0, EXPIRES_AT));
    }

    @Test
    void ignoresProductIdAndRemainingStock() {
        StockReservedPayload withoutIgnored =
                new StockReservedPayload(RESERVATION, SALE, null, USER, 1, 0, EXPIRES_AT);
        StockReservedPayload withIgnored =
                new StockReservedPayload(RESERVATION, SALE, PRODUCT, USER, 1, 99, EXPIRES_AT);

        assertEquals(translator.translate(withIgnored), translator.translate(withoutIgnored));
    }

    private void assertRejected(StockReservedPayload payload) {
        assertThrows(IllegalArgumentException.class, () -> translator.translate(payload));
    }

    private static StockReservedPayload payload(
            String reservationId, String saleId, String userId, int quantity, String expiresAt) {
        return new StockReservedPayload(reservationId, saleId, PRODUCT, userId, quantity, 7, expiresAt);
    }
}
