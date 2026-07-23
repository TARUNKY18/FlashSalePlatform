package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.domain.vo.SaleId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class StockDecrementLuaExecutorTest {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("1be9f50b-300c-4fb8-9402-6f1350a537f8")
    );
    private static final String STOCK_KEY =
            "stock:{1be9f50b-300c-4fb8-9402-6f1350a537f8}";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<Long> script = mock(RedisScript.class);

    private final StockDecrementLuaExecutor executor =
            new StockDecrementLuaExecutor(redisTemplate, script);

    @ParameterizedTest
    @ValueSource(longs = {-2L, -1L, 0L, 42L})
    void executesWithHashTaggedKeyAndReturnsRawScriptResult(long scriptResult) {
        when(redisTemplate.execute(script, List.of(STOCK_KEY), "3"))
                .thenReturn(scriptResult);

        Long result = executor.execute(SALE_ID, 3);

        assertEquals(scriptResult, result);
        verify(redisTemplate).execute(script, List.of(STOCK_KEY), "3");
    }

    @Test
    void rejectsMissingSaleIdBeforeCallingRedis() {
        assertThrows(NullPointerException.class, () -> executor.execute(null, 1));
        verifyNoInteractions(redisTemplate);
    }
}
