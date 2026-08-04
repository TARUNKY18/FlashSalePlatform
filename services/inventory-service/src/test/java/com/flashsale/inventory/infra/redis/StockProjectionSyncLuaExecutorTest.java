package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class StockProjectionSyncLuaExecutorTest {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("ddc3b86d-7a82-49a5-9f91-7c5960f981a6")
    );
    private static final String STOCK_KEY =
            "stock:{ddc3b86d-7a82-49a5-9f91-7c5960f981a6}";
    private static final String VERSION_KEY =
            "stock:version:{ddc3b86d-7a82-49a5-9f91-7c5960f981a6}";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<Long> script = mock(RedisScript.class);

    private final StockProjectionSyncLuaExecutor executor =
            new StockProjectionSyncLuaExecutor(redisTemplate, script);

    @ParameterizedTest
    @ValueSource(longs = {-2L, -1L, 1L, 2L, 3L})
    void executesWithCoLocatedHashTaggedKeysAndReturnsRawResult(long scriptResult) {
        when(redisTemplate.execute(
                script,
                List.of(STOCK_KEY, VERSION_KEY),
                "41",
                "7"
        )).thenReturn(scriptResult);

        Long result = executor.execute(SALE_ID, StockCount.of(41), 7L);

        assertEquals(scriptResult, result);
        verify(redisTemplate).execute(
                script,
                List.of(STOCK_KEY, VERSION_KEY),
                "41",
                "7"
        );
    }

    @Test
    void rejectsInvalidInputBeforeCallingRedis() {
        assertThrows(
                NullPointerException.class,
                () -> executor.execute(null, StockCount.of(1), 0L)
        );
        assertThrows(
                NullPointerException.class,
                () -> executor.execute(SALE_ID, null, 0L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(SALE_ID, StockCount.of(1), -1L)
        );
        verifyNoInteractions(redisTemplate);
    }
}
