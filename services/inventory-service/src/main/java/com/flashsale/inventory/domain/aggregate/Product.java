package com.flashsale.inventory.domain.aggregate;

import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root that owns all sale allocations for one product.
 *
 * <p>The aggregate treats every owned {@link StockLevel} as an active allocation because
 * InventoryContext deliberately does not know the SaleContext lifecycle. Allocation and
 * the approved durable fallback decrement are the only mutations in the current scope;
 * release and reconciliation belong to later work.
 */
public final class Product {

    private final ProductId id;
    private final StockCount totalStock;
    private final Map<SaleId, StockLevel> stockLevelsBySale;
    private long version;

    private Product(
            ProductId id,
            StockCount totalStock,
            Collection<StockLevel> stockLevels,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.totalStock = Objects.requireNonNull(totalStock, "totalStock must not be null");
        Objects.requireNonNull(stockLevels, "stockLevels must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }

        this.stockLevelsBySale = new LinkedHashMap<>();
        for (StockLevel stockLevel : stockLevels) {
            addExistingStockLevel(Objects.requireNonNull(
                    stockLevel,
                    "stockLevels must not contain null"
            ));
        }
        ensureWithinTotalStock();
        this.version = version;
    }

    public static Product create(StockCount totalStock) {
        return new Product(ProductId.generate(), totalStock, List.of(), 0L);
    }

    public static Product create(ProductId id, StockCount totalStock) {
        return new Product(id, totalStock, List.of(), 0L);
    }

    /**
     * Recreates an aggregate while applying the same ownership, uniqueness, and allocation
     * checks as a newly-created Product.
     */
    public static Product reconstitute(
            ProductId id,
            StockCount totalStock,
            Collection<StockLevel> stockLevels,
            long version
    ) {
        return new Product(id, totalStock, stockLevels, version);
    }

    /**
     * Allocates positive stock to a sale.
     *
     * <p>One Product can own at most one StockLevel for a given SaleId, and allocations
     * across all owned StockLevels can never exceed the Product's total stock.
     */
    public StockLevel allocateStock(SaleId saleId, StockCount quantity) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (quantity.isSoldOut()) {
            throw new IllegalArgumentException("Allocation quantity must be positive");
        }
        if (stockLevelsBySale.containsKey(saleId)) {
            throw new IllegalStateException("Stock is already allocated for sale " + saleId);
        }

        long proposedAllocation = allocatedStockValue() + quantity.value();
        if (proposedAllocation > totalStock.value()) {
            throw new IllegalStateException(
                    "Allocation would exceed total product stock of " + totalStock.value()
            );
        }

        long nextVersion = Math.incrementExact(version);
        StockLevel stockLevel = StockLevel.allocate(id, saleId, quantity);
        stockLevelsBySale.put(saleId, stockLevel);
        version = nextVersion;
        return stockLevel;
    }

    /**
     * Attempts to decrement the StockLevel owned for a sale.
     *
     * <p>An empty result means the currently available stock is insufficient. The aggregate
     * remains unchanged in that case. A successful decrement replaces the immutable owned
     * StockLevel and advances only that entity's version.
     */
    public Optional<StockCount> decrementStock(SaleId saleId, int quantity) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "Stock quantity must be positive: " + quantity
            );
        }

        StockLevel stockLevel = Optional.ofNullable(stockLevelsBySale.get(saleId))
                .orElseThrow(() -> new NoSuchElementException(
                        "Product " + id + " has no StockLevel for sale " + saleId
                ));
        if (!stockLevel.currentStock().canDecrement(quantity)) {
            return Optional.empty();
        }

        StockCount remainingStock = stockLevel.currentStock().decrement(quantity);
        StockLevel decrementedStockLevel = StockLevel.reconstitute(
                stockLevel.id(),
                stockLevel.productId(),
                stockLevel.saleId(),
                stockLevel.totalAllocated(),
                remainingStock,
                Math.incrementExact(stockLevel.version())
        );
        stockLevelsBySale.put(saleId, decrementedStockLevel);
        return Optional.of(remainingStock);
    }

    private void addExistingStockLevel(StockLevel stockLevel) {
        if (!id.equals(stockLevel.productId())) {
            throw new IllegalArgumentException("StockLevel belongs to a different Product");
        }
        StockLevel existing = stockLevelsBySale.putIfAbsent(stockLevel.saleId(), stockLevel);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Product cannot contain more than one StockLevel for sale "
                            + stockLevel.saleId()
            );
        }
    }

    private void ensureWithinTotalStock() {
        if (allocatedStockValue() > totalStock.value()) {
            throw new IllegalArgumentException(
                    "Allocated stock cannot exceed total product stock"
            );
        }
    }

    private long allocatedStockValue() {
        return stockLevelsBySale.values().stream()
                .mapToLong(stockLevel -> stockLevel.totalAllocated().value())
                .sum();
    }

    public ProductId id() {
        return id;
    }

    public StockCount totalStock() {
        return totalStock;
    }

    public StockCount allocatedStock() {
        return new StockCount(Math.toIntExact(allocatedStockValue()));
    }

    public StockCount availableToAllocate() {
        return new StockCount(totalStock.value() - allocatedStock().value());
    }

    public Optional<StockLevel> stockLevelFor(SaleId saleId) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        return Optional.ofNullable(stockLevelsBySale.get(saleId));
    }

    public List<StockLevel> stockLevels() {
        return List.copyOf(stockLevelsBySale.values());
    }

    public long version() {
        return version;
    }
}
