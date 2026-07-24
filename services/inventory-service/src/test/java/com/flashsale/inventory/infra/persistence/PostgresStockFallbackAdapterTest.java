package com.flashsale.inventory.infra.persistence;

import static jakarta.persistence.LockModeType.PESSIMISTIC_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.lang.reflect.Method;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

class PostgresStockFallbackAdapterTest {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("51407d9c-26c5-4be9-9f55-4d6ca8ce20b0");
    private static final UUID SALE_UUID =
            UUID.fromString("a444307e-1bb5-4da9-a1ed-743979669b76");
    private static final UUID STOCK_LEVEL_UUID =
            UUID.fromString("8ae66357-b6ed-46ed-9671-418f5e7263ca");
    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_UUID);
    private static final SaleId SALE_ID = SaleId.of(SALE_UUID);

    private final SpringDataProductRepository springDataRepository =
            mock(SpringDataProductRepository.class);
    private final ProductPersistenceMapper mapper = new ProductPersistenceMapper();
    private final PostgresStockFallbackAdapter adapter =
            new PostgresStockFallbackAdapter(springDataRepository, mapper);

    @Test
    void decrementsThroughLockedProductAndFlushesManagedStockLevel() {
        ProductJpaEntity lockedProduct = productEntity(10);
        when(springDataRepository.findByIdForUpdate(PRODUCT_UUID))
                .thenReturn(Optional.of(lockedProduct));

        Optional<StockCount> result = adapter.decrement(PRODUCT_ID, SALE_ID, 3);

        assertEquals(Optional.of(StockCount.of(7)), result);
        assertEquals(7, lockedProduct.getStockLevels().getFirst().getCurrentStock());
        verify(springDataRepository).findByIdForUpdate(PRODUCT_UUID);
        verify(springDataRepository).flush();
    }

    @Test
    void returnsSoldOutWithoutUpdatingOrFlushing() {
        ProductJpaEntity lockedProduct = productEntity(2);
        when(springDataRepository.findByIdForUpdate(PRODUCT_UUID))
                .thenReturn(Optional.of(lockedProduct));

        Optional<StockCount> result = adapter.decrement(PRODUCT_ID, SALE_ID, 3);

        assertTrue(result.isEmpty());
        assertEquals(2, lockedProduct.getStockLevels().getFirst().getCurrentStock());
        verify(springDataRepository, never()).flush();
    }

    @Test
    void rejectsMissingProductAfterLockedLookup() {
        when(springDataRepository.findByIdForUpdate(PRODUCT_UUID))
                .thenReturn(Optional.empty());

        assertThrows(
                NoSuchElementException.class,
                () -> adapter.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        verify(springDataRepository, never()).flush();
    }

    @Test
    void declaresPessimisticWriteLockAndTransactionalFallback() throws Exception {
        Method lockedLookup = SpringDataProductRepository.class.getDeclaredMethod(
                "findByIdForUpdate",
                UUID.class
        );
        Lock lock = lockedLookup.getAnnotation(Lock.class);
        Method decrement = PostgresStockFallbackAdapter.class.getDeclaredMethod(
                "decrement",
                ProductId.class,
                SaleId.class,
                int.class
        );

        assertEquals(PESSIMISTIC_WRITE, lock.value());
        assertTrue(decrement.isAnnotationPresent(Transactional.class));
    }

    private ProductJpaEntity productEntity(int currentStock) {
        ProductJpaEntity product = new ProductJpaEntity(PRODUCT_UUID, 100, 4L);
        product.addStockLevel(new StockLevelJpaEntity(
                STOCK_LEVEL_UUID,
                SALE_UUID,
                10,
                currentStock,
                6L
        ));
        return product;
    }

}
