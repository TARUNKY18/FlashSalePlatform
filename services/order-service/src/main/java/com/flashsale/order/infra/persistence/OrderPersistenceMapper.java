package com.flashsale.order.infra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.event.OrderCreated;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Translation boundary between the Order domain and JPA/JSON models. */
@Component
public class OrderPersistenceMapper {

    private final ObjectMapper objectMapper;

    public OrderPersistenceMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OrderJpaEntity toOrderEntity(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        Instant confirmedAt = null;
        Instant cancelledAt = null;
        Instant expiredAt = null;
        String cancelReason = null;
        if (order.status() instanceof Order.Status.Confirmed confirmed) {
            confirmedAt = confirmed.confirmedAt();
        } else if (order.status() instanceof Order.Status.Cancelled cancelled) {
            cancelledAt = cancelled.cancelledAt();
            cancelReason = cancelled.reason();
        } else if (order.status() instanceof Order.Status.Expired expired) {
            expiredAt = expired.expiredAt();
        }
        return new OrderJpaEntity(
                order.id().value(),
                order.userId().value(),
                order.saleId().value(),
                order.purchaseIntentId().value(),
                order.status().getClass().getSimpleName().toUpperCase(),
                order.amount().amount(),
                order.amount().currency().getCurrencyCode(),
                order.idempotencyKey(),
                confirmedAt,
                cancelledAt,
                expiredAt,
                cancelReason,
                order.version(),
                order.createdAt(),
                order.createdAt()
        );
    }

    public OrderOutboxJpaEntity toOutboxEntity(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        OrderCreated event = order.outboxEvent().event();
        OrderCreated.Payload payload = event.payload();
        ObjectNode json = objectMapper.createObjectNode();
        json.put("orderId", payload.orderId().toString());
        json.put("reservationId", payload.purchaseIntentId().value().toString());
        json.put("userId", payload.userId().value().toString());
        json.put("saleId", payload.saleId().value().toString());
        json.put("amount", payload.amount().amount().toPlainString());
        json.put("currency", payload.amount().currency().getCurrencyCode());
        return new OrderOutboxJpaEntity(
                order.outboxEvent().id().value(),
                order.id().value(),
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.occurredAt(),
                event.aggregateId().value(),
                event.aggregateType(),
                json,
                order.outboxEvent().published(),
                order.outboxEvent().publishedAt(),
                order.outboxEvent().createdAt()
        );
    }
}
