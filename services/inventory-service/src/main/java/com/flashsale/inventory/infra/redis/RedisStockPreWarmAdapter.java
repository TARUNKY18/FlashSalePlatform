package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.application.PreWarmStockResult;
import com.flashsale.inventory.application.port.StockPreWarmPort;
import com.flashsale.inventory.application.port.StockPreWarmUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

/**
 * Redis adapter for revision-fenced atomic pre-warm of the stock projection pair.
 */
@Component
public class RedisStockPreWarmAdapter implements StockPreWarmPort {

    private static final long WARMED = 1L;
    private static final long UPDATED = 2L;
    private static final long ALREADY_CURRENT = 3L;
    private static final long STALE_IGNORED = 4L;
    private static final long INVALID_STATE = -1L;

    private final StockPreWarmLuaExecutor luaExecutor;

    public RedisStockPreWarmAdapter(StockPreWarmLuaExecutor luaExecutor) {
        this.luaExecutor = luaExecutor;
    }

    @Override
    public PreWarmStockResult preWarm(
            SaleId saleId,
            StockCount stock,
            long revision,
            Duration ttl
    ) {
        final Long rawResult;
        try {
            rawResult = luaExecutor.execute(saleId, stock, revision, ttl);
        } catch (DataAccessException | SerializationException exception) {
            throw new StockPreWarmUnavailableException(
                    "Redis stock pre-warm failed with indeterminate outcome",
                    exception
            );
        }

        if (rawResult == null) {
            throw new StockPreWarmUnavailableException(
                    "Redis stock pre-warm returned null",
                    new IllegalStateException("Pre-warm Lua result must not be null")
            );
        }
        if (rawResult == WARMED) {
            return PreWarmStockResult.WARMED;
        }
        if (rawResult == UPDATED) {
            return PreWarmStockResult.UPDATED;
        }
        if (rawResult == ALREADY_CURRENT) {
            return PreWarmStockResult.ALREADY_CURRENT;
        }
        if (rawResult == STALE_IGNORED) {
            return PreWarmStockResult.STALE_IGNORED;
        }
        if (rawResult == INVALID_STATE) {
            return PreWarmStockResult.INVALID_STATE;
        }
        throw new StockPreWarmUnavailableException(
                "Redis stock pre-warm returned an unknown result: " + rawResult,
                new IllegalStateException("Unexpected pre-warm Lua result")
        );
    }
}
