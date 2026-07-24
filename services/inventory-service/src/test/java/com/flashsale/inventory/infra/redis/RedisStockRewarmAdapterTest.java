package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.port.StockRewarmPort;
import com.flashsale.inventory.application.port.StockRewarmUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisStockRewarmAdapterTest {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("47d305ef-409d-4087-bde2-9340538d16d7")
    );

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final StockRewarmPort adapter = new RedisStockRewarmAdapter(redisTemplate);

    @Test
    void restoresMissingCounterFromDurableRemainingStock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("stock:{" + SALE_ID + "}", "41"))
                .thenReturn(true);

        adapter.rewarmIfAbsent(SALE_ID, StockCount.of(41));

        verify(valueOperations).setIfAbsent("stock:{" + SALE_ID + "}", "41");
    }

    @Test
    void leavesExistingCounterUnchanged() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("stock:{" + SALE_ID + "}", "41"))
                .thenReturn(false);

        adapter.rewarmIfAbsent(SALE_ID, StockCount.of(41));

        verify(valueOperations).setIfAbsent("stock:{" + SALE_ID + "}", "41");
    }

    @Test
    void translatesRedisConnectionFailureToPortOwnedUnavailableSignal() {
        RedisConnectionFailureException connectionFailure =
                new RedisConnectionFailureException("Redis is unavailable");
        when(redisTemplate.opsForValue()).thenThrow(connectionFailure);

        StockRewarmUnavailableException exception = assertThrows(
                StockRewarmUnavailableException.class,
                () -> adapter.rewarmIfAbsent(SALE_ID, StockCount.of(41))
        );

        assertInstanceOf(RedisConnectionFailureException.class, exception.getCause());
    }
}
