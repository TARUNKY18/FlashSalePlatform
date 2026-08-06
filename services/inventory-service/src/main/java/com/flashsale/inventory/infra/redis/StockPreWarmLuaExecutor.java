package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Executes the revision-fenced stock pre-warm Lua script.
 */
@Component
public class StockPreWarmLuaExecutor {

    private static final String STOCK_KEY_PREFIX = "stock:{";
    private static final String VERSION_KEY_PREFIX = "stock:version:{";
    private static final String KEY_SUFFIX = "}";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> stockPreWarmScript;

    public StockPreWarmLuaExecutor(
            StringRedisTemplate redisTemplate,
            @Qualifier("stockPreWarmScript") RedisScript<Long> stockPreWarmScript
    ) {
        this.redisTemplate = redisTemplate;
        this.stockPreWarmScript = stockPreWarmScript;
    }

    public Long execute(SaleId saleId, StockCount stock, long revision, Duration ttl) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(stock, "stock must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        String hashTag = saleId.toString();
        return redisTemplate.execute(
                stockPreWarmScript,
                List.of(
                        STOCK_KEY_PREFIX + hashTag + KEY_SUFFIX,
                        VERSION_KEY_PREFIX + hashTag + KEY_SUFFIX
                ),
                Integer.toString(stock.value()),
                Long.toString(revision),
                Long.toString(ttl.toMillis())
        );
    }
}
