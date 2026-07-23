package com.flashsale.inventory.infra.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA representation of the Product aggregate root.
 *
 * <p>This class contains persistence structure only. Domain rules remain in
 * {@code com.flashsale.inventory.domain}.
 */
@Entity
@Table(name = "products")
public class ProductJpaEntity {

    @Id
    private UUID id;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(
            mappedBy = "product",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private List<StockLevelJpaEntity> stockLevels = new ArrayList<>();

    /**
     * Required by JPA.
     */
    protected ProductJpaEntity() {
    }

    ProductJpaEntity(UUID id, int totalStock, long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.totalStock = totalStock;
        this.version = version;
    }

    void addStockLevel(StockLevelJpaEntity stockLevel) {
        StockLevelJpaEntity child = Objects.requireNonNull(
                stockLevel,
                "stockLevel must not be null"
        );
        child.attachTo(this);
        stockLevels.add(child);
    }

    public UUID getId() {
        return id;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public long getVersion() {
        return version;
    }

    public List<StockLevelJpaEntity> getStockLevels() {
        return Collections.unmodifiableList(stockLevels);
    }
}
