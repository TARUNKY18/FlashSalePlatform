package com.flashsale.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StockCounterServiceTest {

    private static final ProductId PRODUCT_ID = ProductId.of(
            UUID.fromString("e2c798f3-3266-40c2-81f0-e779b0de47cf")
    );
    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("ae0ef409-9744-46ce-b1c8-72cf11446b8e")
    );

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final StockDecrementPort stockDecrementPort = mock(StockDecrementPort.class);
    private final StockCounterService service =
            new StockCounterService(productRepository, stockDecrementPort);

    @Test
    void mapsSuccessfulDecrementToRemainingStock() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 3)).thenReturn(42L);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 3);

        StockDecrementResult.Decremented decremented =
                assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(StockCount.of(42), decremented.remainingStock());
        verify(productRepository).findById(PRODUCT_ID);
        verify(stockDecrementPort).decrement(SALE_ID, 3);
        verify(productRepository, never()).save(any());
    }

    @Test
    void mapsSoldOutWithoutPersisting() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(-1L);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 1);

        assertInstanceOf(StockDecrementResult.SoldOut.class, result);
        verify(productRepository, never()).save(any());
    }

    @Test
    void exposesCacheMissWithoutFallbackRetryOrPersistence() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(-2L);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 1);

        assertInstanceOf(StockDecrementResult.CacheMiss.class, result);
        verify(stockDecrementPort, times(1)).decrement(SALE_ID, 1);
        verify(productRepository, never()).save(any());
    }

    @Test
    void rejectsMissingProductBeforeCallingRedis() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThrows(
                NoSuchElementException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        verifyNoInteractions(stockDecrementPort);
    }

    @Test
    void rejectsSaleWithoutOwnedStockLevelBeforeCallingRedis() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThrows(
                NoSuchElementException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        verifyNoInteractions(stockDecrementPort);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void delegatesQuantityBoundsToOwnedStockCount(int invalidQuantity) {
        arrangeProductWithAllocation();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, invalidQuantity)
        );

        verifyNoInteractions(stockDecrementPort);
    }

    @Test
    void rejectsNullPortResult() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );
    }

    @Test
    void rejectsUnknownNegativePortResult() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(-3L);

        assertThrows(
                IllegalStateException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );
    }

    @Test
    void rejectsRemainingStockOutsideDomainRange() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1))
                .thenReturn((long) Integer.MAX_VALUE + 1L);

        assertThrows(
                IllegalStateException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );
    }

    private void arrangeProductWithAllocation() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(SALE_ID, StockCount.of(100));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    }
}
