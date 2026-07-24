package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.StockLevelId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The only translation boundary between the Inventory domain and JPA models.
 */
@Component
public class ProductPersistenceMapper {

    public ProductJpaEntity toJpaEntity(Product product) {
        Product aggregate = Objects.requireNonNull(product, "product must not be null");
        ProductJpaEntity productEntity = new ProductJpaEntity(
                aggregate.id().value(),
                aggregate.totalStock().value(),
                aggregate.version()
        );
        aggregate.stockLevels().stream()
                .map(this::toJpaEntity)
                .forEach(productEntity::addStockLevel);
        return productEntity;
    }

    public Product toDomain(ProductJpaEntity productEntity) {
        ProductJpaEntity entity = Objects.requireNonNull(
                productEntity,
                "productEntity must not be null"
        );
        List<StockLevel> stockLevels = entity.getStockLevels().stream()
                .map(stockLevelEntity -> toDomain(entity, stockLevelEntity))
                .toList();
        return Product.reconstitute(
                ProductId.of(entity.getId()),
                StockCount.of(entity.getTotalStock()),
                stockLevels,
                entity.getVersion()
        );
    }

    /**
     * Applies the current stock of one owned domain StockLevel to its managed JPA entity.
     *
     * <p>JPA remains responsible for advancing the persisted optimistic version when the
     * surrounding transaction flushes.
     */
    public void applyCurrentStock(
            Product product,
            ProductJpaEntity productEntity,
            SaleId saleId
    ) {
        Product aggregate = Objects.requireNonNull(product, "product must not be null");
        ProductJpaEntity entity = Objects.requireNonNull(
                productEntity,
                "productEntity must not be null"
        );
        Objects.requireNonNull(saleId, "saleId must not be null");
        if (!aggregate.id().value().equals(entity.getId())) {
            throw new IllegalArgumentException(
                    "Domain Product and JPA Product identities must match"
            );
        }

        StockLevel domainStockLevel = aggregate.stockLevelFor(saleId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Product " + aggregate.id()
                                + " has no StockLevel for sale " + saleId
                ));
        StockLevelJpaEntity jpaStockLevel = entity.getStockLevels().stream()
                .filter(stockLevel -> stockLevel.getId().equals(
                        domainStockLevel.id().value()
                ))
                .filter(stockLevel -> stockLevel.getSaleId().equals(saleId.value()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Managed Product " + aggregate.id()
                                + " does not contain the expected StockLevel for sale "
                                + saleId
                ));
        jpaStockLevel.updateCurrentStock(domainStockLevel.currentStock().value());
    }

    private StockLevelJpaEntity toJpaEntity(StockLevel stockLevel) {
        return new StockLevelJpaEntity(
                stockLevel.id().value(),
                stockLevel.saleId().value(),
                stockLevel.totalAllocated().value(),
                stockLevel.currentStock().value(),
                stockLevel.version()
        );
    }

    private StockLevel toDomain(
            ProductJpaEntity expectedOwner,
            StockLevelJpaEntity stockLevelEntity
    ) {
        ProductJpaEntity actualOwner = stockLevelEntity.getProduct();
        if (actualOwner == null || !expectedOwner.getId().equals(actualOwner.getId())) {
            throw new IllegalStateException(
                    "Persisted StockLevel does not belong to Product " + expectedOwner.getId()
            );
        }
        return StockLevel.reconstitute(
                StockLevelId.of(stockLevelEntity.getId()),
                ProductId.of(expectedOwner.getId()),
                SaleId.of(stockLevelEntity.getSaleId()),
                StockCount.of(stockLevelEntity.getTotalAllocated()),
                StockCount.of(stockLevelEntity.getCurrentStock()),
                stockLevelEntity.getVersion()
        );
    }
}
