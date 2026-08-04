package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Executes the revision-fenced stock projection synchronization Lua script.
 */
@Component
public class StockProjectionSyncLuaExecutor {

    private static final String STOCK_KEY_PREFIX = "stock:{";
    private static final String VERSION_KEY_PREFIX = "stock:version:{";
    private static final String KEY_SUFFIX = "}";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> stockProjectionSyncScript;

    public StockProjectionSyncLuaExecutor(
            StringRedisTemplate redisTemplate,
            @Qualifier("stockProjectionSyncScript")
            RedisScript<Long> stockProjectionSyncScript
    ) {
        this.redisTemplate = redisTemplate;
        this.stockProjectionSyncScript = stockProjectionSyncScript;
    }

    public Long execute(
            SaleId saleId,
            StockCount durableStock,
            long durableRevision
    ) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(durableStock, "durableStock must not be null");
        if (durableRevision < 0) {
            throw new IllegalArgumentException("durableRevision must not be negative");
        }

        String hashTag = saleId.toString();
        return redisTemplate.execute(
                stockProjectionSyncScript,
                List.of(
                        STOCK_KEY_PREFIX + hashTag + KEY_SUFFIX,
                        VERSION_KEY_PREFIX + hashTag + KEY_SUFFIX
                ),
                Integer.toString(durableStock.value()),
                Long.toString(durableRevision)
        );
    }
}
