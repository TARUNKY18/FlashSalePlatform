package com.flashsale.inventory.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.StockLevelId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductPersistenceMapperTest {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("060e9db5-583e-4efd-80aa-63b4507525bd");
    private static final UUID STOCK_LEVEL_UUID =
            UUID.fromString("42bb39e9-4378-4a43-ae76-08963938163c");
    private static final UUID SALE_UUID =
            UUID.fromString("77757fa1-7ba8-4407-86b4-abf92424e578");

    private final ProductPersistenceMapper mapper = new ProductPersistenceMapper();

    @Test
    void mapsCompleteDomainAggregateToJpaTree() {
        Product domain = domainProduct();

        ProductJpaEntity productEntity = mapper.toJpaEntity(domain);

        assertEquals(PRODUCT_UUID, productEntity.getId());
        assertEquals(100, productEntity.getTotalStock());
        assertEquals(5L, productEntity.getVersion());
        assertEquals(1, productEntity.getStockLevels().size());

        StockLevelJpaEntity stockLevelEntity = productEntity.getStockLevels().getFirst();
        assertEquals(STOCK_LEVEL_UUID, stockLevelEntity.getId());
        assertEquals(SALE_UUID, stockLevelEntity.getSaleId());
        assertEquals(40, stockLevelEntity.getTotalAllocated());
        assertEquals(12, stockLevelEntity.getCurrentStock());
        assertEquals(3L, stockLevelEntity.getVersion());
        assertSame(productEntity, stockLevelEntity.getProduct());
    }

    @Test
    void mapsCompleteJpaTreeToDomainAggregate() {
        ProductJpaEntity productEntity = jpaProduct(12);

        Product domain = mapper.toDomain(productEntity);

        assertEquals(ProductId.of(PRODUCT_UUID), domain.id());
        assertEquals(StockCount.of(100), domain.totalStock());
        assertEquals(StockCount.of(40), domain.allocatedStock());
        assertEquals(StockCount.of(60), domain.availableToAllocate());
        assertEquals(5L, domain.version());

        StockLevel stockLevel = domain.stockLevelFor(SaleId.of(SALE_UUID)).orElseThrow();
        assertEquals(StockLevelId.of(STOCK_LEVEL_UUID), stockLevel.id());
        assertEquals(StockCount.of(40), stockLevel.totalAllocated());
        assertEquals(StockCount.of(12), stockLevel.currentStock());
        assertEquals(3L, stockLevel.version());
    }

    @Test
    void domainValidationRejectsInvalidPersistedStock() {
        ProductJpaEntity productEntity = jpaProduct(41);

        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(productEntity));
    }

    private Product domainProduct() {
        ProductId productId = ProductId.of(PRODUCT_UUID);
        StockLevel stockLevel = StockLevel.reconstitute(
                StockLevelId.of(STOCK_LEVEL_UUID),
                productId,
                SaleId.of(SALE_UUID),
                StockCount.of(40),
                StockCount.of(12),
                3L
        );
        return Product.reconstitute(
                productId,
                StockCount.of(100),
                List.of(stockLevel),
                5L
        );
    }

    private ProductJpaEntity jpaProduct(int currentStock) {
        ProductJpaEntity productEntity = new ProductJpaEntity(PRODUCT_UUID, 100, 5L);
        productEntity.addStockLevel(new StockLevelJpaEntity(
                STOCK_LEVEL_UUID,
                SALE_UUID,
                40,
                currentStock,
                3L
        ));
        return productEntity;
    }
}
