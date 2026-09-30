package com.flashsale.order.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.application.OrderCommandService;
import com.flashsale.order.domain.vo.PurchaseIntent;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

class InventoryEventConsumerTest {

    private static final String RESERVATION = "11111111-1111-4111-8111-111111111111";
    private static final String SALE = "22222222-2222-4222-8222-222222222222";
    private static final String PRODUCT = "33333333-3333-4333-8333-333333333333";
    private static final String USER = "44444444-4444-4444-8444-444444444444";
    private static final String EXPIRES_AT = "2026-06-15T12:10:00Z";

    private final OrderCommandService commandService = mock(OrderCommandService.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final InventoryEventConsumer consumer =
            new InventoryEventConsumer(new ObjectMapper(), new InventoryEventTranslator(), commandService);

    @Test
    void stockReservedIsTranslatedRecordedAndAcknowledged() {
        consumer.consume(record(stockReserved(RESERVATION, 2)), ack);

        verify(commandService).processReservationConfirmed(new PurchaseIntent(
                PurchaseIntentId.of(RESERVATION), UserId.of(USER), SaleId.of(SALE), 2,
                Instant.parse(EXPIRES_AT)));
        verify(ack).acknowledge();
    }

    @Test
    void unknownEnvelopeAndPayloadFieldsAreTolerated() {
        String envelope = stockReserved(RESERVATION, 1)
                .replace("\"eventVersion\"", "\"traceId\":\"t\",\"eventVersion\"")
                .replace("\"quantity\"", "\"sourceOfTruth\":\"REDIS\",\"quantity\"");

        consumer.consume(record(envelope), ack);

        verify(commandService).processReservationConfirmed(any(PurchaseIntent.class));
        verify(ack).acknowledge();
    }

    @Test
    void invalidPayloadIsTerminalAndAcknowledgedWithoutProcessing() {
        consumer.consume(record(stockReserved("not-a-uuid", 1)), ack);
        consumer.consume(record(stockReserved(RESERVATION, 0)), ack);
        consumer.consume(record("{\"eventType\":\"StockReserved\"}"), ack);
        consumer.consume(record("{\"eventType\":\"StockReserved\",\"payload\":{\"quantity\":\"x\"}}"), ack);
        consumer.consume(record("not json"), ack);
        consumer.consume(record(null), ack);

        verifyNoInteractions(commandService);
        verify(ack, times(6)).acknowledge();
    }

    @Test
    void otherEventTypesAreAcknowledgedWithoutProcessing() {
        consumer.consume(record(stockReserved(RESERVATION, 1)
                .replace("StockReserved", "ReservationExpired")), ack);
        consumer.consume(record("{\"eventId\":\"e\"}"), ack);

        verifyNoInteractions(commandService);
        verify(ack, times(2)).acknowledge();
    }

    @Test
    void processingFailureIsNotAcknowledged() {
        doThrow(new IllegalStateException("database down"))
                .when(commandService).processReservationConfirmed(any());

        assertThrows(IllegalStateException.class,
                () -> consumer.consume(record(stockReserved(RESERVATION, 1)), ack));

        verify(ack, never()).acknowledge();
    }

    @Test
    void listenerSubscribesToInventoryEventsWithResolvedGroup() throws Exception {
        KafkaListener listener = InventoryEventConsumer.class
                .getMethod("consume", ConsumerRecord.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class);

        assertArrayEquals(new String[] {"inventory-events"}, listener.topics());
        assertEquals("order-svc-reservation-consumer", listener.groupId());
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("inventory-events", 0, 0L, PRODUCT, value);
    }

    private static String stockReserved(String reservationId, int quantity) {
        return """
                {"eventId":"55555555-5555-4555-8555-555555555555","eventType":"StockReserved",
                 "eventVersion":"1.0","occurredAt":"2026-06-15T12:00:00Z",
                 "aggregateId":"%1$s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%1$s","saleId":"%2$s","productId":"%3$s",
                            "userId":"%4$s","quantity":%5$d,"remainingStock":142,
                            "expiresAt":"%6$s"}}
                """.formatted(reservationId, SALE, PRODUCT, USER, quantity, EXPIRES_AT);
    }
}
