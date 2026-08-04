package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.application.port.StockDecrementUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.dao.QueryTimeoutException;

class RedisStockDecrementAdapterTest {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("a5965ded-ff1e-4ef7-81b7-b6607a153bdd")
    );

    private final StockDecrementLuaExecutor luaExecutor =
            mock(StockDecrementLuaExecutor.class);
    private final StockDecrementPort adapter =
            new RedisStockDecrementAdapter(luaExecutor);

    @ParameterizedTest
    @ValueSource(longs = {-2L, -1L, 0L, 42L})
    void delegatesAndReturnsRawExecutorResult(long executorResult) {
        when(luaExecutor.execute(SALE_ID, 3)).thenReturn(executorResult);

        Long result = adapter.decrement(SALE_ID, 3);

        assertEquals(executorResult, result);
        verify(luaExecutor).execute(SALE_ID, 3);
    }

    @Test
    void doesNotTranslateNullExecutorResult() {
        when(luaExecutor.execute(SALE_ID, 1)).thenReturn(null);

        Long result = adapter.decrement(SALE_ID, 1);

        assertNull(result);
        verify(luaExecutor).execute(SALE_ID, 1);
    }

    @Test
    void translatesRedisConnectionFailureToPortOwnedUnavailableSignal() {
        RedisConnectionFailureException connectionFailure =
                new RedisConnectionFailureException("Redis is unavailable");
        when(luaExecutor.execute(SALE_ID, 1)).thenThrow(connectionFailure);

        StockDecrementUnavailableException exception = assertThrows(
                StockDecrementUnavailableException.class,
                () -> adapter.decrement(SALE_ID, 1)
        );

        assertInstanceOf(RedisConnectionFailureException.class, exception.getCause());
        verify(luaExecutor).execute(SALE_ID, 1);
    }

    @Test
    void translatesCommandTimeoutToPortOwnedUnavailableSignal() {
        QueryTimeoutException timeout = new QueryTimeoutException("Redis timed out");
        when(luaExecutor.execute(SALE_ID, 1)).thenThrow(timeout);

        StockDecrementUnavailableException exception = assertThrows(
                StockDecrementUnavailableException.class,
                () -> adapter.decrement(SALE_ID, 1)
        );

        assertSame(timeout, exception.getCause());
        verify(luaExecutor).execute(SALE_ID, 1);
    }

    @Test
    void deterministicScriptFailureRemainsFailClosed() {
        RedisSystemException scriptFailure = new RedisSystemException(
                "Lua failed",
                new IllegalStateException("deterministic script error")
        );
        when(luaExecutor.execute(SALE_ID, 1)).thenThrow(scriptFailure);

        RedisSystemException result = assertThrows(
                RedisSystemException.class,
                () -> adapter.decrement(SALE_ID, 1)
        );

        assertSame(scriptFailure, result);
    }

    @Test
    void deterministicSerializationFailureRemainsFailClosed() {
        SerializationException serializationFailure =
                new SerializationException("invalid serialized value");
        when(luaExecutor.execute(SALE_ID, 1)).thenThrow(serializationFailure);

        SerializationException result = assertThrows(
                SerializationException.class,
                () -> adapter.decrement(SALE_ID, 1)
        );

        assertSame(serializationFailure, result);
    }
}
