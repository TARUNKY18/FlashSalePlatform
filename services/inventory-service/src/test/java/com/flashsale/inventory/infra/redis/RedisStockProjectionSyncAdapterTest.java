package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.StockProjectionSyncResult;
import com.flashsale.inventory.application.port.StockProjectionSyncPort;
import com.flashsale.inventory.application.port.StockProjectionSyncUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;

class RedisStockProjectionSyncAdapterTest {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("47d305ef-409d-4087-bde2-9340538d16d7")
    );
    private static final StockCount DURABLE_STOCK = StockCount.of(41);

    private final StockProjectionSyncLuaExecutor luaExecutor =
            mock(StockProjectionSyncLuaExecutor.class);
    private final StockProjectionSyncPort adapter =
            new RedisStockProjectionSyncAdapter(luaExecutor);

    @ParameterizedTest
    @MethodSource("recognizedResults")
    void mapsRecognizedScriptResults(
            long rawResult,
            StockProjectionSyncResult expected
    ) {
        when(luaExecutor.execute(SALE_ID, DURABLE_STOCK, 7L))
                .thenReturn(rawResult);

        StockProjectionSyncResult result =
                adapter.synchronize(SALE_ID, DURABLE_STOCK, 7L);

        assertEquals(expected, result);
        verify(luaExecutor).execute(SALE_ID, DURABLE_STOCK, 7L);
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void translatesInvalidStoredDataAndResultContractFailures(Long rawResult) {
        when(luaExecutor.execute(SALE_ID, DURABLE_STOCK, 7L))
                .thenReturn(rawResult);

        StockProjectionSyncUnavailableException exception = assertThrows(
                StockProjectionSyncUnavailableException.class,
                () -> adapter.synchronize(SALE_ID, DURABLE_STOCK, 7L)
        );

        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    @Test
    void translatesRedisAccessFailure() {
        RedisConnectionFailureException failure =
                new RedisConnectionFailureException("Redis unavailable");
        when(luaExecutor.execute(SALE_ID, DURABLE_STOCK, 7L))
                .thenThrow(failure);

        StockProjectionSyncUnavailableException exception = assertThrows(
                StockProjectionSyncUnavailableException.class,
                () -> adapter.synchronize(SALE_ID, DURABLE_STOCK, 7L)
        );

        assertSame(failure, exception.getCause());
    }

    @Test
    void translatesSerializationFailure() {
        SerializationException failure = new SerializationException("invalid value");
        when(luaExecutor.execute(SALE_ID, DURABLE_STOCK, 7L))
                .thenThrow(failure);

        StockProjectionSyncUnavailableException exception = assertThrows(
                StockProjectionSyncUnavailableException.class,
                () -> adapter.synchronize(SALE_ID, DURABLE_STOCK, 7L)
        );

        assertSame(failure, exception.getCause());
    }

    private static Stream<Arguments> recognizedResults() {
        return Stream.of(
                Arguments.of(1L, StockProjectionSyncResult.APPLIED),
                Arguments.of(2L, StockProjectionSyncResult.STALE_IGNORED),
                Arguments.of(3L, StockProjectionSyncResult.MISSING)
        );
    }

    private static Stream<Long> invalidResults() {
        return Stream.of(-1L, -2L, 0L, 4L, null);
    }
}
