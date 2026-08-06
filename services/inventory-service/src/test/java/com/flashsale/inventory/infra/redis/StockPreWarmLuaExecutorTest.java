package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class StockPreWarmLuaExecutorTest {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d")
    );
    private static final String STOCK_KEY =
            "stock:{1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d}";
    private static final String VERSION_KEY =
            "stock:version:{1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d}";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final RedisScript<Long> script = mock(RedisScript.class);

    private final StockPreWarmLuaExecutor executor =
            new StockPreWarmLuaExecutor(redisTemplate, script);

    @ParameterizedTest
    @ValueSource(longs = {-1L, 1L, 2L, 3L, 4L})
    void executesWithCoLocatedHashTaggedKeysAndReturnsRawResult(long scriptResult) {
        when(redisTemplate.execute(
                script,
                List.of(STOCK_KEY, VERSION_KEY),
                "100",
                "5",
                "300000"
        )).thenReturn(scriptResult);

        Long result = executor.execute(SALE_ID, StockCount.of(100), 5L, Duration.ofMinutes(5));

        assertEquals(scriptResult, result);
        verify(redisTemplate).execute(
                script,
                List.of(STOCK_KEY, VERSION_KEY),
                "100",
                "5",
                "300000"
        );
    }

    @Test
    void rejectsInvalidInputBeforeCallingRedis() {
        assertThrows(NullPointerException.class,
                () -> executor.execute(null, StockCount.of(1), 0L, Duration.ofMinutes(1)));
        assertThrows(NullPointerException.class,
                () -> executor.execute(SALE_ID, null, 0L, Duration.ofMinutes(1)));
        assertThrows(NullPointerException.class,
                () -> executor.execute(SALE_ID, StockCount.of(1), 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(SALE_ID, StockCount.of(1), -1L, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(SALE_ID, StockCount.of(1), 0L, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(SALE_ID, StockCount.of(1), 0L, Duration.ofSeconds(-1)));
        verifyNoInteractions(redisTemplate);
    }
}
