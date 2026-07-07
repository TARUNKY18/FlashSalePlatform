package com.flashsale.sale.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping for the {@code flash_sales} table (schema.sql).
 *
 * <p>Deliberately a plain data-holder with no domain behaviour — the sealed
 * {@code SaleStatus} interface is never mapped directly to JPA (Build-Plan.md Week 2
 * risk mitigation: "map status as VARCHAR, reconstruct sealed type in domain layer").
 * Mapping between this entity and the {@code FlashSale} aggregate happens exclusively in
 * {@link SaleRepository}.
 */
@Entity
@Table(name = "flash_sales")
public class FlashSaleJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    /** SCHEDULED | ACTIVE | ENDED | ARCHIVED — see flash_sales_status_ck in schema.sql. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** TIME_ELAPSED | STOCK_DEPLETED | ADMIN_FORCE — null until the sale ends. */
    @Column(name = "end_reason", length = 50)
    private String endReason;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected FlashSaleJpaEntity() {
    }

    public FlashSaleJpaEntity(UUID id, String name, UUID productId, int totalStock, String status,
                               Instant scheduledAt, Instant activatedAt, Instant endedAt, Instant archivedAt,
                               String endReason, long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.productId = productId;
        this.totalStock = totalStock;
        this.status = status;
        this.scheduledAt = scheduledAt;
        this.activatedAt = activatedAt;
        this.endedAt = endedAt;
        this.archivedAt = archivedAt;
        this.endReason = endReason;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public UUID getProductId() {
        return productId;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public String getStatus() {
        return status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public String getEndReason() {
        return endReason;
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
