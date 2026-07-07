package com.flashsale.sale.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code sale_schedules} table (schema.sql) — the
 * {@code SaleSchedule} entity's persistence representation.
 */
@Entity
@Table(name = "sale_schedules")
public class SaleScheduleJpaEntity {

    @Id
    private UUID id;

    @Column(name = "sale_id", nullable = false, unique = true)
    private UUID saleId;

    @Column(name = "sale_start", nullable = false)
    private Instant saleStart;

    @Column(name = "sale_end", nullable = false)
    private Instant saleEnd;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SaleScheduleJpaEntity() {
    }

    public SaleScheduleJpaEntity(UUID id, UUID saleId, Instant saleStart, Instant saleEnd, String timezone,
                                  long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.saleId = saleId;
        this.saleStart = saleStart;
        this.saleEnd = saleEnd;
        this.timezone = timezone;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public Instant getSaleStart() {
        return saleStart;
    }

    public Instant getSaleEnd() {
        return saleEnd;
    }

    public String getTimezone() {
        return timezone;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
