package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.application.port.StockRewarmPort;
import com.flashsale.inventory.application.port.StockRewarmUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Objects;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis implementation of safe stock-counter re-warming.
 *
 * <p>The atomic set-if-absent operation restores a missing counter from authoritative
 * durable stock without overwriting a value restored or decremented by another request.
 */
@Component
public class RedisStockRewarmAdapter implements StockRewarmPort {

    private static final String STOCK_KEY_PREFIX = "stock:{";
    private static final String STOCK_KEY_SUFFIX = "}";

    private final StringRedisTemplate redisTemplate;

    public RedisStockRewarmAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void rewarmIfAbsent(SaleId saleId, StockCount remainingStock) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(remainingStock, "remainingStock must not be null");

        String stockKey = STOCK_KEY_PREFIX + saleId + STOCK_KEY_SUFFIX;
        try {
            redisTemplate.opsForValue().setIfAbsent(
                    stockKey,
                    Integer.toString(remainingStock.value())
            );
        } catch (RedisConnectionFailureException exception) {
            throw new StockRewarmUnavailableException(
                    "Primary stock counter could not be re-warmed",
                    exception
            );
        }
    }
}
