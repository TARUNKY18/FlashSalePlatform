package com.flashsale.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.StockPreWarmPort;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreWarmStockUseCaseTest {

    private static final ProductId PRODUCT_ID = ProductId.of(
            UUID.fromString("11111111-1111-1111-1111-111111111111")
    );
    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("22222222-2222-2222-2222-222222222222")
    );

    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");
    // Sale starts in 30s — within the [preWarmAt, saleStart) execution window
    private static final Instant SALE_START = NOW.plusSeconds(30);
    private static final Instant SALE_END = NOW.plusSeconds(3600);

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final StockPreWarmPort stockPreWarmPort = mock(StockPreWarmPort.class);
    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PreWarmStockUseCase useCase =
            new PreWarmStockUseCase(productRepository, stockPreWarmPort, fixedClock);

    @Test
    void throwsWhenSaleEndNotAfterSaleStart() {
        assertThrows(IllegalArgumentException.class,
                () -> useCase.preWarm(PRODUCT_ID, SALE_ID, SALE_START, SALE_START));
        assertThrows(IllegalArgumentException.class,
                () -> useCase.preWarm(PRODUCT_ID, SALE_ID, SALE_START, SALE_START.minusSeconds(1)));
        verifyNoInteractions(productRepository, stockPreWarmPort);
    }

    @Test
    void returnsNotDueWhenBeforePreWarmWindow() {
        // saleStart is more than 60s from now → preWarmAt is still in the future
        Instant farFutureSaleStart = NOW.plusSeconds(120);

        PreWarmStockResult result = useCase.preWarm(
                PRODUCT_ID, SALE_ID, farFutureSaleStart, farFutureSaleStart.plusSeconds(3600)
        );

        assertEquals(PreWarmStockResult.NOT_DUE, result);
        verifyNoInteractions(productRepository, stockPreWarmPort);
    }

    @Test
    void returnsMissedWindowWhenAtSaleStart() {
        // now == saleStart → window closed
        PreWarmStockResult result = useCase.preWarm(
                PRODUCT_ID, SALE_ID, NOW, NOW.plusSeconds(3600)
        );

        assertEquals(PreWarmStockResult.MISSED_WINDOW, result);
        verifyNoInteractions(productRepository, stockPreWarmPort);
    }

    @Test
    void returnsMissedWindowWhenAfterSaleStart() {
        Instant pastSaleStart = NOW.minusSeconds(30);

        PreWarmStockResult result = useCase.preWarm(
                PRODUCT_ID, SALE_ID, pastSaleStart, pastSaleStart.plusSeconds(3600)
        );

        assertEquals(PreWarmStockResult.MISSED_WINDOW, result);
        verifyNoInteractions(productRepository, stockPreWarmPort);
    }

    @Test
    void throwsWhenProductNotFound() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThrows(
                NoSuchElementException.class,
                () -> useCase.preWarm(PRODUCT_ID, SALE_ID, SALE_START, SALE_END)
        );

        verifyNoInteractions(stockPreWarmPort);
    }

    @Test
    void throwsWhenStockLevelNotFound() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThrows(
                NoSuchElementException.class,
                () -> useCase.preWarm(PRODUCT_ID, SALE_ID, SALE_START, SALE_END)
        );

        verifyNoInteractions(stockPreWarmPort);
    }

    @Test
    void delegatesToPortWithAuthoritativeSnapshotAndReturnsDelegatedResult() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(SALE_ID, StockCount.of(100));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Duration expectedTtl = Duration.between(NOW, SALE_END.plus(10, ChronoUnit.MINUTES));
        when(stockPreWarmPort.preWarm(SALE_ID, StockCount.of(100), 0L, expectedTtl))
                .thenReturn(PreWarmStockResult.WARMED);

        PreWarmStockResult result = useCase.preWarm(PRODUCT_ID, SALE_ID, SALE_START, SALE_END);

        assertEquals(PreWarmStockResult.WARMED, result);
        verify(stockPreWarmPort).preWarm(SALE_ID, StockCount.of(100), 0L, expectedTtl);
    }

    @Test
    void propagatesAllPortResults() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(SALE_ID, StockCount.of(100));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        Duration expectedTtl = Duration.between(NOW, SALE_END.plus(10, ChronoUnit.MINUTES));

        for (PreWarmStockResult expected : new PreWarmStockResult[]{
                PreWarmStockResult.WARMED,
                PreWarmStockResult.UPDATED,
                PreWarmStockResult.ALREADY_CURRENT,
                PreWarmStockResult.STALE_IGNORED,
                PreWarmStockResult.INVALID_STATE
        }) {
            when(stockPreWarmPort.preWarm(SALE_ID, StockCount.of(100), 0L, expectedTtl))
                    .thenReturn(expected);

            PreWarmStockResult result =
                    useCase.preWarm(PRODUCT_ID, SALE_ID, SALE_START, SALE_END);

            assertEquals(expected, result);
        }
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class,
                () -> useCase.preWarm(null, SALE_ID, SALE_START, SALE_END));
        assertThrows(NullPointerException.class,
                () -> useCase.preWarm(PRODUCT_ID, null, SALE_START, SALE_END));
        assertThrows(NullPointerException.class,
                () -> useCase.preWarm(PRODUCT_ID, SALE_ID, null, SALE_END));
        assertThrows(NullPointerException.class,
                () -> useCase.preWarm(PRODUCT_ID, SALE_ID, SALE_START, null));
        verifyNoInteractions(productRepository, stockPreWarmPort);
    }
}
