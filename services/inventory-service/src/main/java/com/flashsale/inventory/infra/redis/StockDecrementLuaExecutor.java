package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.domain.vo.SaleId;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Thin Redis adapter for the approved atomic stock-decrement Lua script.
 *
 * <p>This class deliberately returns the script's raw value. Interpreting cache-miss,
 * sold-out, and successful-decrement results belongs to a later application-layer service.
 */
@Component
public class StockDecrementLuaExecutor {

    private static final String STOCK_KEY_PREFIX = "stock:{";
    private static final String STOCK_KEY_SUFFIX = "}";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> stockDecrementScript;

    public StockDecrementLuaExecutor(
            StringRedisTemplate redisTemplate,
            @Qualifier("stockDecrementScript") RedisScript<Long> stockDecrementScript
    ) {
        this.redisTemplate = redisTemplate;
        this.stockDecrementScript = stockDecrementScript;
    }

    /**
     * Executes the Lua script with its required key and quantity argument.
     *
     * @return the unmodified Lua result, including {@code -2}, {@code -1}, or a
     *         non-negative remaining stock count
     */
    public Long execute(SaleId saleId, int quantity) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        String stockKey = STOCK_KEY_PREFIX + saleId + STOCK_KEY_SUFFIX;
        return redisTemplate.execute(
                stockDecrementScript,
                List.of(stockKey),
                Integer.toString(quantity)
        );
    }
}
