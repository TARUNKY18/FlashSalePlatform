package com.flashsale.order.infra.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persistence-only representation of one order outbox row. */
@Entity
@Table(name = "order_outbox")
public class OrderOutboxJpaEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false, length = 10)
    private String eventVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderOutboxJpaEntity() {}

    OrderOutboxJpaEntity(
            UUID id,
            UUID orderId,
            UUID eventId,
            String eventType,
            String eventVersion,
            Instant occurredAt,
            UUID aggregateId,
            String aggregateType,
            JsonNode payload,
            boolean published,
            Instant publishedAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.eventVersion = Objects.requireNonNull(eventVersion, "eventVersion must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.published = published;
        this.publishedAt = publishedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void markPublished(Instant at) {
        published = true;
        publishedAt = Objects.requireNonNull(at, "at must not be null");
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getEventVersion() { return eventVersion; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getAggregateId() { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public JsonNode getPayload() { return payload; }
    public boolean isPublished() { return published; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
