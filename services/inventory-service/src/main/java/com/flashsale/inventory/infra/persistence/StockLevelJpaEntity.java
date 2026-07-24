package com.flashsale.inventory.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA representation of a StockLevel owned by a Product aggregate.
 */
@Entity
@Table(
        name = "stock_levels",
        uniqueConstraints = @UniqueConstraint(
                name = "stock_levels_product_sale_unique",
                columnNames = {"product_id", "sale_id"}
        )
)
public class StockLevelJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private ProductJpaEntity product;

    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @Column(name = "total_allocated", nullable = false, updatable = false)
    private int totalAllocated;

    @Column(name = "current_stock", nullable = false)
    private int currentStock;

    @Version
    @Column(nullable = false)
    private long version;

    /**
     * Required by JPA.
     */
    protected StockLevelJpaEntity() {
    }

    StockLevelJpaEntity(
            UUID id,
            UUID saleId,
            int totalAllocated,
            int currentStock,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.saleId = Objects.requireNonNull(saleId, "saleId must not be null");
        this.totalAllocated = totalAllocated;
        this.currentStock = currentStock;
        this.version = version;
    }

    void attachTo(ProductJpaEntity product) {
        ProductJpaEntity owner = Objects.requireNonNull(product, "product must not be null");
        if (this.product != null && this.product != owner) {
            throw new IllegalStateException("StockLevel is already attached to another Product");
        }
        this.product = owner;
    }

    void updateCurrentStock(int currentStock) {
        if (currentStock < 0 || currentStock > totalAllocated) {
            throw new IllegalArgumentException(
                    "currentStock must be between zero and totalAllocated"
            );
        }
        this.currentStock = currentStock;
    }

    public UUID getId() {
        return id;
    }

    public ProductJpaEntity getProduct() {
        return product;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public int getTotalAllocated() {
        return totalAllocated;
    }

    public int getCurrentStock() {
        return currentStock;
    }

    public long getVersion() {
        return version;
    }
}
