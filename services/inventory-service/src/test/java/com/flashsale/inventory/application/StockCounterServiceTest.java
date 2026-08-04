package com.flashsale.inventory.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flashsale.inventory.application.port.DurableStockDecrementPort;
import com.flashsale.inventory.application.port.DurableStockUnavailableException;
import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.application.port.StockDecrementUnavailableException;
import com.flashsale.inventory.application.port.StockProjectionSyncPort;
import com.flashsale.inventory.application.port.StockProjectionSyncUnavailableException;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.lang.reflect.Method;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

class StockCounterServiceTest {

    private static final ProductId PRODUCT_ID = ProductId.of(
            UUID.fromString("e2c798f3-3266-40c2-81f0-e779b0de47cf")
    );
    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("ae0ef409-9744-46ce-b1c8-72cf11446b8e")
    );

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final StockDecrementPort stockDecrementPort = mock(StockDecrementPort.class);
    private final DurableStockDecrementPort durableStockDecrementPort =
            mock(DurableStockDecrementPort.class);
    private final StockProjectionSyncPort stockProjectionSyncPort =
            mock(StockProjectionSyncPort.class);
    private final StockCounterService service =
            new StockCounterService(
                    productRepository,
                    stockDecrementPort,
                    durableStockDecrementPort,
                    stockProjectionSyncPort
            );

    @ParameterizedTest
    @ValueSource(longs = {-2L, -1L, 0L, 42L})
    void everyRecognizedRedisOutcomeInvokesDurableOnceAndReturnsDurableSuccess(
            long redisResult
    ) {
        arrangeProductWithAllocation();
        DurableStockDecrementResult.Decremented durableResult =
                new DurableStockDecrementResult.Decremented(StockCount.of(41), 7L);
        when(stockDecrementPort.decrement(SALE_ID, 3)).thenReturn(redisResult);
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 3))
                .thenReturn(durableResult);
        when(stockProjectionSyncPort.synchronize(SALE_ID, StockCount.of(41), 7L))
                .thenReturn(StockProjectionSyncResult.APPLIED);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 3);

        StockDecrementResult.Decremented decremented =
                assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(StockCount.of(41), decremented.remainingStock());
        var ordered = inOrder(
                stockDecrementPort,
                durableStockDecrementPort,
                stockProjectionSyncPort
        );
        ordered.verify(stockDecrementPort).decrement(SALE_ID, 3);
        ordered.verify(durableStockDecrementPort)
                .decrement(PRODUCT_ID, SALE_ID, 3);
        ordered.verify(stockProjectionSyncPort)
                .synchronize(SALE_ID, StockCount.of(41), 7L);
        verify(productRepository, never()).save(any());
    }

    @Test
    void indeterminateRedisFailureInvokesDurableOnce() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 2))
                .thenThrow(new StockDecrementUnavailableException(
                        "indeterminate",
                        new RuntimeException("connection lost")
                ));
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 2))
                .thenReturn(new DurableStockDecrementResult.Decremented(
                        StockCount.of(38),
                        8L
                ));
        when(stockProjectionSyncPort.synchronize(SALE_ID, StockCount.of(38), 8L))
                .thenReturn(StockProjectionSyncResult.APPLIED);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 2);

        StockDecrementResult.Decremented decremented =
                assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(StockCount.of(38), decremented.remainingStock());
        verify(stockDecrementPort, times(1)).decrement(SALE_ID, 2);
        verify(durableStockDecrementPort, times(1))
                .decrement(PRODUCT_ID, SALE_ID, 2);
        verify(stockProjectionSyncPort, times(1))
                .synchronize(SALE_ID, StockCount.of(38), 8L);
    }

    @Test
    void durableInsufficiencyDeterminesSoldOutAndSynchronizesLockedState() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 3)).thenReturn(42L);
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 3))
                .thenReturn(new DurableStockDecrementResult.Insufficient(
                        StockCount.of(2),
                        6L
                ));
        when(stockProjectionSyncPort.synchronize(SALE_ID, StockCount.of(2), 6L))
                .thenReturn(StockProjectionSyncResult.APPLIED);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 3);

        assertInstanceOf(StockDecrementResult.SoldOut.class, result);
        verify(stockProjectionSyncPort)
                .synchronize(SALE_ID, StockCount.of(2), 6L);
    }

    @Test
    void synchronizationFailurePreservesCommittedDurableSuccess() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 2)).thenReturn(-2L);
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 2))
                .thenReturn(new DurableStockDecrementResult.Decremented(
                        StockCount.of(38),
                        8L
                ));
        doThrow(new StockProjectionSyncUnavailableException(
                "unavailable",
                new RuntimeException("connection failed")
        )).when(stockProjectionSyncPort)
                .synchronize(SALE_ID, StockCount.of(38), 8L);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 2);

        StockDecrementResult.Decremented decremented =
                assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(StockCount.of(38), decremented.remainingStock());
        verify(durableStockDecrementPort, times(1))
                .decrement(PRODUCT_ID, SALE_ID, 2);
        verify(stockProjectionSyncPort, times(1))
                .synchronize(SALE_ID, StockCount.of(38), 8L);
    }

    @Test
    void nullSynchronizationResultPreservesCommittedDurableInsufficiency() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 2)).thenReturn(-1L);
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 2))
                .thenReturn(new DurableStockDecrementResult.Insufficient(
                        StockCount.of(1),
                        8L
                ));
        when(stockProjectionSyncPort.synchronize(SALE_ID, StockCount.of(1), 8L))
                .thenReturn(null);

        StockDecrementResult result = service.decrement(PRODUCT_ID, SALE_ID, 2);

        assertInstanceOf(StockDecrementResult.SoldOut.class, result);
    }

    @ParameterizedTest
    @MethodSource("projectionMismatches")
    void warnsWhenRedisAndPostgresDisagree(
            long redisResult,
            DurableStockDecrementResult durableResult
    ) {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(redisResult);
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 1))
                .thenReturn(durableResult);
        StockCount durableStock =
                durableResult instanceof DurableStockDecrementResult.Decremented decremented
                        ? decremented.remainingStock()
                        : ((DurableStockDecrementResult.Insufficient) durableResult)
                                .currentStock();
        long revision =
                durableResult instanceof DurableStockDecrementResult.Decremented decremented
                        ? decremented.revision()
                        : ((DurableStockDecrementResult.Insufficient) durableResult).revision();
        when(stockProjectionSyncPort.synchronize(SALE_ID, durableStock, revision))
                .thenReturn(StockProjectionSyncResult.APPLIED);

        List<ILoggingEvent> warnings = captureWarnings(
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        assertTrue(warnings.stream().anyMatch(event -> event.getLevel() == Level.WARN));
    }

    @Test
    void rejectsMissingProductBeforeCallingRedis() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThrows(
                NoSuchElementException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        verifyNoInteractions(stockDecrementPort);
        verifyNoInteractions(durableStockDecrementPort);
        verifyNoInteractions(stockProjectionSyncPort);
    }

    @Test
    void productValidationInfrastructureFailureDoesNotInvokeRedis() {
        DurableStockUnavailableException failure =
                new DurableStockUnavailableException(
                        "validation read unavailable",
                        new RuntimeException("database unavailable")
                );
        when(productRepository.findById(PRODUCT_ID)).thenThrow(failure);

        DurableStockUnavailableException result = assertThrows(
                DurableStockUnavailableException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        assertEquals(failure, result);
        verifyNoInteractions(stockDecrementPort);
        verifyNoInteractions(durableStockDecrementPort);
        verifyNoInteractions(stockProjectionSyncPort);
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
        verifyNoInteractions(durableStockDecrementPort);
        verifyNoInteractions(stockProjectionSyncPort);
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
        verifyNoInteractions(durableStockDecrementPort);
        verifyNoInteractions(stockProjectionSyncPort);
    }

    @ParameterizedTest
    @MethodSource("invalidRedisResults")
    void invalidRedisResultFailsClosedWithoutDurableInvocation(Long redisResult) {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(redisResult);

        assertThrows(
                IllegalStateException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        verifyNoInteractions(durableStockDecrementPort);
        verifyNoInteractions(stockProjectionSyncPort);
    }

    @Test
    void nullDurableResultFailsClosedWithoutSynchronization() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(-2L);
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 1))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        verifyNoInteractions(stockProjectionSyncPort);
    }

    @Test
    void durableFailureCannotReturnSuccessOrSynchronize() {
        arrangeProductWithAllocation();
        when(stockDecrementPort.decrement(SALE_ID, 1)).thenReturn(4L);
        DurableStockUnavailableException failure =
                new DurableStockUnavailableException(
                        "durable unavailable",
                        new RuntimeException("commit failed")
                );
        when(durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 1))
                .thenThrow(failure);

        DurableStockUnavailableException result = assertThrows(
                DurableStockUnavailableException.class,
                () -> service.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        assertEquals(failure, result);
        verifyNoInteractions(stockProjectionSyncPort);
    }

    @Test
    void serviceIsNotTransactional() throws Exception {
        Method method = StockCounterService.class.getDeclaredMethod(
                "decrement",
                ProductId.class,
                SaleId.class,
                int.class
        );

        assertTrue(!StockCounterService.class.isAnnotationPresent(Transactional.class));
        assertTrue(!method.isAnnotationPresent(Transactional.class));
    }

    private List<ILoggingEvent> captureWarnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(StockCounterService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private void arrangeProductWithAllocation() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(SALE_ID, StockCount.of(100));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    }

    private static Stream<Arguments> projectionMismatches() {
        return Stream.of(
                Arguments.of(
                        -1L,
                        new DurableStockDecrementResult.Decremented(
                                StockCount.of(41),
                                7L
                        )
                ),
                Arguments.of(
                        41L,
                        new DurableStockDecrementResult.Insufficient(
                                StockCount.of(0),
                                7L
                        )
                ),
                Arguments.of(
                        42L,
                        new DurableStockDecrementResult.Decremented(
                                StockCount.of(41),
                                7L
                        )
                )
        );
    }

    private static Stream<Long> invalidRedisResults() {
        return Stream.of(null, -3L, (long) Integer.MAX_VALUE + 1L);
    }
}
