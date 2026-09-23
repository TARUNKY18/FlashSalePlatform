package com.flashsale.order.domain.vo;

import java.util.Objects;
import java.util.UUID;

/** Typed identity for an Order-owned outbox event. */
public record OutboxEventId(UUID value) {

    public OutboxEventId {
        Objects.requireNonNull(value, "OutboxEventId must not be null");
    }

    public static OutboxEventId generate() {
        return new OutboxEventId(UUID.randomUUID());
    }
}
