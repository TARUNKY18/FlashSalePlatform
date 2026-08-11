package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.domain.vo.SaleId;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Executes the stock-release Lua script.
 *
 * <p>KEYS[1]: {@code stock:{saleId}}
 * ARGV[1]: quantity to restore
 * ARGV[2]: total_allocated ceiling
 */
@Component
public class StockReleaseLuaExecutor {

    private static final String STOCK_KEY_PREFIX = "stock:{";
    private static final String KEY_SUFFIX = "}";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> stockReleaseScript;

    public StockReleaseLuaExecutor(
            StringRedisTemplate redisTemplate,
            @Qualifier("stockReleaseScript") RedisScript<Long> stockReleaseScript
    ) {
        this.redisTemplate = redisTemplate;
        this.stockReleaseScript = stockReleaseScript;
    }

    public Long execute(SaleId saleId, int quantity, int ceiling) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (ceiling <= 0) {
            throw new IllegalArgumentException("ceiling must be positive");
        }
        String key = STOCK_KEY_PREFIX + saleId + KEY_SUFFIX;
        return redisTemplate.execute(
                stockReleaseScript,
                List.of(key),
                Integer.toString(quantity),
                Integer.toString(ceiling)
        );
    }
}
