package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.domain.vo.SaleId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings("unchecked")
class StockReleaseLuaExecutorTest {

    private StringRedisTemplate redisTemplate;
    private RedisScript<Long> script;
    private StockReleaseLuaExecutor executor;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        script        = mock(RedisScript.class);
        executor      = new StockReleaseLuaExecutor(redisTemplate, script);
    }

    @Test
    void usesHashTaggedStockKey() {
        SaleId saleId = SaleId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                any(String.class), any(String.class))).thenReturn(50L);

        executor.execute(saleId, 5, 100);

        verify(redisTemplate).execute(
                eq(script),
                eq(List.of("stock:{aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa}")),
                eq("5"),
                eq("100")
        );
    }

    @Test
    void propagatesRawResult() {
        SaleId saleId = SaleId.of(UUID.randomUUID());
        when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                any(String.class), any(String.class))).thenReturn(42L);

        Long result = executor.execute(saleId, 3, 100);

        assertEquals(42L, result);
    }

    @Test
    void propagatesMissingKeyResult() {
        SaleId saleId = SaleId.of(UUID.randomUUID());
        when(redisTemplate.execute(any(RedisScript.class), any(List.class),
                any(String.class), any(String.class))).thenReturn(-2L);

        assertEquals(-2L, executor.execute(saleId, 1, 50));
    }

    @Test
    void rejectsNullSaleId() {
        assertThrows(NullPointerException.class, () -> executor.execute(null, 1, 100));
    }

    @Test
    void rejectsZeroQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(SaleId.of(UUID.randomUUID()), 0, 100));
    }

    @Test
    void rejectsNegativeQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(SaleId.of(UUID.randomUUID()), -1, 100));
    }

    @Test
    void rejectsZeroCeiling() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(SaleId.of(UUID.randomUUID()), 1, 0));
    }
}
