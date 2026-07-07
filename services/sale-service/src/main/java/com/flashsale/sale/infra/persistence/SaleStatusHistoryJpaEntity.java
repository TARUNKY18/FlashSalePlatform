package com.flashsale.sale.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for {@code sale_status_history} (schema.sql) — an immutable, insert-only
 * audit log (US-004). No {@code @Version}: rows are never updated, only inserted.
 */
@Entity
@Table(name = "sale_status_history")
public class SaleStatusHistoryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    /** Null for the initial SCHEDULED entry (schema.sql: "NULL for initial SCHEDULED entry"). */
    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "transitioned_at", nullable = false)
    private Instant transitionedAt;

    /** 'SCHEDULER' | 'ADMIN:{userId}' — Week 2 uses the "SYSTEM" placeholder (see SaleCommandService). */
    @Column(nullable = false, length = 100)
    private String actor;

    @Column(length = 255)
    private String reason;

    protected SaleStatusHistoryJpaEntity() {
    }

    public SaleStatusHistoryJpaEntity(UUID id, UUID saleId, String fromStatus, String toStatus,
                                       Instant transitionedAt, String actor, String reason) {
        this.id = id;
        this.saleId = saleId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.transitionedAt = transitionedAt;
        this.actor = actor;
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public Instant getTransitionedAt() {
        return transitionedAt;
    }

    public String getActor() {
        return actor;
    }

    public String getReason() {
        return reason;
    }
}
