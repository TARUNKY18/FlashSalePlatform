package com.flashsale.inventory.infra.persistence;

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

/** Persistence-only representation of one inventory outbox row. */
@Entity
@Table(name = "inventory_outbox")
public class InventoryOutboxJpaEntity {

    @Id
    private UUID id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false, length = 10)
    private String eventVersion;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    private String aggregateType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published", nullable = false)
    private boolean published;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected InventoryOutboxJpaEntity() {
    }

    InventoryOutboxJpaEntity(
            UUID id,
            UUID reservationId,
            UUID eventId,
            String eventType,
            String eventVersion,
            UUID aggregateId,
            String aggregateType,
            JsonNode payload,
            Instant occurredAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.eventVersion = Objects.requireNonNull(eventVersion, "eventVersion must not be null");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public void markPublished(Instant at) {
        published = true;
        publishedAt = Objects.requireNonNull(at, "at must not be null");
        lastAttemptedAt = at;
    }

    public void recordFailure(Instant at, String error) {
        attemptCount = Math.incrementExact(attemptCount);
        lastAttemptedAt = Objects.requireNonNull(at, "at must not be null");
        lastError = Objects.requireNonNull(error, "error must not be null");
    }

    public UUID getId() { return id; }
    public UUID getReservationId() { return reservationId; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getEventVersion() { return eventVersion; }
    public UUID getAggregateId() { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public JsonNode getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public boolean isPublished() { return published; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getLastAttemptedAt() { return lastAttemptedAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
}
