package com.flashsale.order.domain.event;

import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.OrderId;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Domain representation of the canonical OrderCreated envelope and payload. */
public record OrderCreated(
        UUID eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        OrderId aggregateId,
        String aggregateType,
        Payload payload
) {
    public static final String EVENT_TYPE = "OrderCreated";
    public static final String EVENT_VERSION = "1.0";
    public static final String AGGREGATE_TYPE = "Order";

    public OrderCreated {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be " + EVENT_TYPE);
        }
        if (!EVENT_VERSION.equals(eventVersion)) {
            throw new IllegalArgumentException("eventVersion must be " + EVENT_VERSION);
        }
        if (!AGGREGATE_TYPE.equals(aggregateType)) {
            throw new IllegalArgumentException("aggregateType must be " + AGGREGATE_TYPE);
        }
        if (!aggregateId.equals(payload.orderId())) {
            throw new IllegalArgumentException("aggregateId must equal payload orderId");
        }
    }

    public static OrderCreated create(
            OrderId orderId,
            PurchaseIntentId purchaseIntentId,
            UserId userId,
            SaleId saleId,
            Money amount,
            Instant occurredAt
    ) {
        return new OrderCreated(
                UUID.randomUUID(), EVENT_TYPE, EVENT_VERSION, occurredAt, orderId,
                AGGREGATE_TYPE,
                new Payload(orderId, purchaseIntentId, userId, saleId, amount)
        );
    }

    public record Payload(
            OrderId orderId,
            PurchaseIntentId purchaseIntentId,
            UserId userId,
            SaleId saleId,
            Money amount
    ) {
        public Payload {
            Objects.requireNonNull(orderId, "orderId must not be null");
            Objects.requireNonNull(purchaseIntentId, "purchaseIntentId must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(saleId, "saleId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }
}
