package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.PreWarmStockResult;
import com.flashsale.inventory.application.port.StockPreWarmPort;
import com.flashsale.inventory.application.port.StockPreWarmUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.SerializationException;

class RedisStockPreWarmAdapterTest {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
    );
    private static final StockCount STOCK = StockCount.of(50);
    private static final long REVISION = 3L;
    private static final Duration TTL = Duration.ofMinutes(70);

    private final StockPreWarmLuaExecutor luaExecutor = mock(StockPreWarmLuaExecutor.class);
    private final StockPreWarmPort adapter = new RedisStockPreWarmAdapter(luaExecutor);

    @ParameterizedTest
    @MethodSource("recognizedResults")
    void mapsRecognizedScriptResults(long rawResult, PreWarmStockResult expected) {
        when(luaExecutor.execute(SALE_ID, STOCK, REVISION, TTL)).thenReturn(rawResult);

        PreWarmStockResult result = adapter.preWarm(SALE_ID, STOCK, REVISION, TTL);

        assertEquals(expected, result);
        verify(luaExecutor).execute(SALE_ID, STOCK, REVISION, TTL);
    }

    @Test
    void translatesNullResultToUnavailable() {
        when(luaExecutor.execute(SALE_ID, STOCK, REVISION, TTL)).thenReturn(null);

        StockPreWarmUnavailableException exception = assertThrows(
                StockPreWarmUnavailableException.class,
                () -> adapter.preWarm(SALE_ID, STOCK, REVISION, TTL)
        );

        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    @Test
    void translatesUnknownResultToUnavailable() {
        when(luaExecutor.execute(SALE_ID, STOCK, REVISION, TTL)).thenReturn(99L);

        StockPreWarmUnavailableException exception = assertThrows(
                StockPreWarmUnavailableException.class,
                () -> adapter.preWarm(SALE_ID, STOCK, REVISION, TTL)
        );

        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    @Test
    void translatesRedisAccessFailure() {
        RedisConnectionFailureException failure =
                new RedisConnectionFailureException("Redis unavailable");
        when(luaExecutor.execute(SALE_ID, STOCK, REVISION, TTL)).thenThrow(failure);

        StockPreWarmUnavailableException exception = assertThrows(
                StockPreWarmUnavailableException.class,
                () -> adapter.preWarm(SALE_ID, STOCK, REVISION, TTL)
        );

        assertSame(failure, exception.getCause());
    }

    @Test
    void translatesSerializationFailure() {
        SerializationException failure = new SerializationException("invalid");
        when(luaExecutor.execute(SALE_ID, STOCK, REVISION, TTL)).thenThrow(failure);

        StockPreWarmUnavailableException exception = assertThrows(
                StockPreWarmUnavailableException.class,
                () -> adapter.preWarm(SALE_ID, STOCK, REVISION, TTL)
        );

        assertSame(failure, exception.getCause());
    }

    private static Stream<Arguments> recognizedResults() {
        return Stream.of(
                Arguments.of(1L, PreWarmStockResult.WARMED),
                Arguments.of(2L, PreWarmStockResult.UPDATED),
                Arguments.of(3L, PreWarmStockResult.ALREADY_CURRENT),
                Arguments.of(4L, PreWarmStockResult.STALE_IGNORED),
                Arguments.of(-1L, PreWarmStockResult.INVALID_STATE)
        );
    }
}
