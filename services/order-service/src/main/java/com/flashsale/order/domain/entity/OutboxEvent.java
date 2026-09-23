package com.flashsale.order.domain.entity;

import com.flashsale.order.domain.event.OrderCreated;
import com.flashsale.order.domain.vo.OutboxEventId;
import java.time.Instant;
import java.util.Objects;

/** Pending Order-owned event persisted atomically with its aggregate. */
public final class OutboxEvent {

    private final OutboxEventId id;
    private final OrderCreated event;
    private final boolean published;
    private final Instant publishedAt;
    private final Instant createdAt;

    private OutboxEvent(
            OutboxEventId id,
            OrderCreated event,
            boolean published,
            Instant publishedAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.event = Objects.requireNonNull(event, "event must not be null");
        this.published = published;
        this.publishedAt = publishedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!published && publishedAt != null) {
            throw new IllegalArgumentException("an unpublished event cannot have publishedAt");
        }
    }

    public static OutboxEvent pending(OrderCreated event, Instant createdAt) {
        return new OutboxEvent(
                OutboxEventId.generate(), event, false, null, createdAt
        );
    }

    public OutboxEventId id() { return id; }
    public OrderCreated event() { return event; }
    public boolean published() { return published; }
    public Instant publishedAt() { return publishedAt; }
    public Instant createdAt() { return createdAt; }
}
