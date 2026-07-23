package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.domain.vo.SaleId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}
