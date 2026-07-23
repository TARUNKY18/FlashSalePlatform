package com.flashsale.inventory.domain.entity;

import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.StockLevelId;
import java.util.Objects;

/**
 * Stock allocated to one sale inside the {@code Product} aggregate.
 *
 * <p>A new allocation starts with all allocated units available. Reconstituted state may
 * have fewer units available, but current stock can never exceed the original allocation.
 */
public final class StockLevel {

    private final StockLevelId id;
    private final ProductId productId;
    private final SaleId saleId;
    private final StockCount totalAllocated;
    private final StockCount currentStock;
    private final long version;

    private StockLevel(
            StockLevelId id,
            ProductId productId,
            SaleId saleId,
            StockCount totalAllocated,
            StockCount currentStock,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.saleId = Objects.requireNonNull(saleId, "saleId must not be null");
        this.totalAllocated = requirePositiveAllocation(totalAllocated);
        this.currentStock = Objects.requireNonNull(currentStock, "currentStock must not be null");
        if (currentStock.value() > totalAllocated.value()) {
            throw new IllegalArgumentException("Current stock cannot exceed total allocation");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
    }

    /**
     * Creates the initial stock level for a Product allocation.
     */
    public static StockLevel allocate(
            ProductId productId,
            SaleId saleId,
            StockCount totalAllocated
    ) {
        return new StockLevel(
                StockLevelId.generate(),
                productId,
                saleId,
                totalAllocated,
                totalAllocated,
                0L
        );
    }

    /**
     * Recreates domain state without introducing persistence concerns into the entity.
     */
    public static StockLevel reconstitute(
            StockLevelId id,
            ProductId productId,
            SaleId saleId,
            StockCount totalAllocated,
            StockCount currentStock,
            long version
    ) {
        return new StockLevel(id, productId, saleId, totalAllocated, currentStock, version);
    }

    private static StockCount requirePositiveAllocation(StockCount allocation) {
        Objects.requireNonNull(allocation, "totalAllocated must not be null");
        if (allocation.isSoldOut()) {
            throw new IllegalArgumentException("Stock allocation must be positive");
        }
        return allocation;
    }

    public StockLevelId id() {
        return id;
    }

    public ProductId productId() {
        return productId;
    }

    public SaleId saleId() {
        return saleId;
    }

    public StockCount totalAllocated() {
        return totalAllocated;
    }

    public StockCount currentStock() {
        return currentStock;
    }

    public long version() {
        return version;
    }
}
