package com.flashsale.order.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderPersistenceMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2099-01-01T12:00:00Z");
    private final OrderPersistenceMapper mapper =
            new OrderPersistenceMapper(new ObjectMapper());

    @Test
    void mapsOrderWithoutChangingItsIdentityOrState() {
        Order order = order();
        OrderJpaEntity entity = mapper.toOrderEntity(order);

        assertEquals(order.id().value(), entity.getId());
        assertEquals(order.userId().value(), entity.getUserId());
        assertEquals(order.saleId().value(), entity.getSaleId());
        assertEquals(order.purchaseIntentId().value(), entity.getReservationId());
        assertEquals("PENDING", entity.getStatus());
        assertEquals(order.amount().amount(), entity.getAmount());
        assertEquals("USD", entity.getCurrency());
        assertEquals(order.idempotencyKey(), entity.getIdempotencyKey());
        assertEquals(0L, entity.getVersion());
        assertEquals(CREATED_AT, entity.getCreatedAt());
        assertEquals(CREATED_AT, entity.getUpdatedAt());
    }

    @Test
    void mapsCanonicalOrderCreatedOutboxAndExactPayload() {
        Order order = order();
        OrderOutboxJpaEntity entity = mapper.toOutboxEntity(order);

        assertEquals(order.outboxEvent().id().value(), entity.getId());
        assertEquals(order.id().value(), entity.getOrderId());
        assertEquals(order.outboxEvent().event().eventId(), entity.getEventId());
        assertEquals("OrderCreated", entity.getEventType());
        assertEquals("1.0", entity.getEventVersion());
        assertEquals(CREATED_AT, entity.getOccurredAt());
        assertEquals(order.id().value(), entity.getAggregateId());
        assertEquals("Order", entity.getAggregateType());
        assertFalse(entity.isPublished());
        assertNull(entity.getPublishedAt());
        assertEquals(CREATED_AT, entity.getCreatedAt());
        Set<String> fields = new java.util.HashSet<>();
        entity.getPayload().fieldNames().forEachRemaining(fields::add);
        assertEquals(
                Set.of("orderId", "reservationId", "userId", "saleId", "amount", "currency"),
                fields
        );
        assertEquals(order.id().toString(), entity.getPayload().get("orderId").asText());
        assertEquals("19.90", entity.getPayload().get("amount").asText());
    }

    private Order order() {
        return Order.place(
                PurchaseIntentId.of(UUID.fromString("10000000-0000-0000-0000-000000000001")),
                UserId.of(UUID.fromString("20000000-0000-0000-0000-000000000002")),
                SaleId.of(UUID.fromString("30000000-0000-0000-0000-000000000003")),
                Money.of("19.90", "USD"),
                "opaque-key",
                CREATED_AT
        );
    }
}
