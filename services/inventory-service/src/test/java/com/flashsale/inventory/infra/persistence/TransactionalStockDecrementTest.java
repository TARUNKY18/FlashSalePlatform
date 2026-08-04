package com.flashsale.inventory.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.DurableStockDecrementResult;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.StockLevelId;
import java.lang.reflect.Method;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class TransactionalStockDecrementTest {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("51407d9c-26c5-4be9-9f55-4d6ca8ce20b0");
    private static final UUID SALE_UUID =
            UUID.fromString("a444307e-1bb5-4da9-a1ed-743979669b76");
    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_UUID);
    private static final SaleId SALE_ID = SaleId.of(SALE_UUID);

    private final SpringDataProductRepository springDataRepository =
            mock(SpringDataProductRepository.class);
    private final ProductPersistenceMapper mapper = mock(ProductPersistenceMapper.class);
    private final TransactionalStockDecrement decrement =
            new TransactionalStockDecrement(springDataRepository, mapper);

    @Test
    void decrementsManagedStockFlushesAndReturnsPostFlushRevision() {
        ProductJpaEntity lockedProduct = mock(ProductJpaEntity.class);
        StockLevelJpaEntity managedStockLevel = mock(StockLevelJpaEntity.class);
        Product product = productWithStock(10, 6L);
        when(springDataRepository.findByIdForUpdate(PRODUCT_UUID))
                .thenReturn(Optional.of(lockedProduct));
        when(mapper.toDomain(lockedProduct)).thenReturn(product);
        when(mapper.applyCurrentStock(product, lockedProduct, SALE_ID))
                .thenReturn(managedStockLevel);
        when(managedStockLevel.getCurrentStock()).thenReturn(7);
        when(managedStockLevel.getVersion()).thenReturn(7L);

        DurableStockDecrementResult result =
                decrement.decrement(PRODUCT_ID, SALE_ID, 3);

        DurableStockDecrementResult.Decremented success = assertInstanceOf(
                DurableStockDecrementResult.Decremented.class,
                result
        );
        assertEquals(StockCount.of(7), success.remainingStock());
        assertEquals(7L, success.revision());
        verify(mapper).applyCurrentStock(product, lockedProduct, SALE_ID);
        verify(springDataRepository).flush();
    }

    @Test
    void returnsLockedStockAndRevisionWithoutMutationWhenInsufficient() {
        ProductJpaEntity lockedProduct = mock(ProductJpaEntity.class);
        Product product = productWithStock(2, 6L);
        when(springDataRepository.findByIdForUpdate(PRODUCT_UUID))
                .thenReturn(Optional.of(lockedProduct));
        when(mapper.toDomain(lockedProduct)).thenReturn(product);

        DurableStockDecrementResult result =
                decrement.decrement(PRODUCT_ID, SALE_ID, 3);

        DurableStockDecrementResult.Insufficient insufficient = assertInstanceOf(
                DurableStockDecrementResult.Insufficient.class,
                result
        );
        assertEquals(StockCount.of(2), insufficient.currentStock());
        assertEquals(6L, insufficient.revision());
        verify(mapper, never()).applyCurrentStock(product, lockedProduct, SALE_ID);
        verify(springDataRepository, never()).flush();
    }

    @Test
    void rejectsMissingProductAfterLockedLookup() {
        when(springDataRepository.findByIdForUpdate(PRODUCT_UUID))
                .thenReturn(Optional.empty());

        assertThrows(
                NoSuchElementException.class,
                () -> decrement.decrement(PRODUCT_ID, SALE_ID, 1)
        );
    }

    @Test
    void ownsTheTransaction() throws Exception {
        Method method = TransactionalStockDecrement.class.getDeclaredMethod(
                "decrement",
                ProductId.class,
                SaleId.class,
                int.class
        );

        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    private Product productWithStock(int currentStock, long revision) {
        StockLevel stockLevel = StockLevel.reconstitute(
                StockLevelId.of(UUID.fromString(
                        "8ae66357-b6ed-46ed-9671-418f5e7263ca"
                )),
                PRODUCT_ID,
                SALE_ID,
                StockCount.of(10),
                StockCount.of(currentStock),
                revision
        );
        return Product.reconstitute(
                PRODUCT_ID,
                StockCount.of(100),
                List.of(stockLevel),
                4L
        );
    }
}
