package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.application.StockProjectionSyncResult;
import com.flashsale.inventory.application.port.StockProjectionSyncPort;
import com.flashsale.inventory.application.port.StockProjectionSyncUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

/**
 * Redis adapter for revision-fenced synchronization of authoritative durable stock.
 */
@Component
public class RedisStockProjectionSyncAdapter implements StockProjectionSyncPort {

    private static final long APPLIED = 1L;
    private static final long STALE_IGNORED = 2L;
    private static final long MISSING = 3L;
    private static final long INVALID_STORED_STOCK = -1L;
    private static final long INVALID_STORED_REVISION = -2L;

    private final StockProjectionSyncLuaExecutor luaExecutor;

    public RedisStockProjectionSyncAdapter(
            StockProjectionSyncLuaExecutor luaExecutor
    ) {
        this.luaExecutor = luaExecutor;
    }

    @Override
    public StockProjectionSyncResult synchronize(
            SaleId saleId,
            StockCount durableStock,
            long durableRevision
    ) {
        final Long rawResult;
        try {
            rawResult = luaExecutor.execute(saleId, durableStock, durableRevision);
        } catch (DataAccessException | SerializationException exception) {
            throw unavailable("Redis stock projection synchronization failed", exception);
        }

        if (rawResult == null) {
            throw unavailable(
                    "Redis stock projection synchronization returned null",
                    new IllegalStateException("Synchronization Lua result must not be null")
            );
        }
        if (rawResult == APPLIED) {
            return StockProjectionSyncResult.APPLIED;
        }
        if (rawResult == STALE_IGNORED) {
            return StockProjectionSyncResult.STALE_IGNORED;
        }
        if (rawResult == MISSING) {
            return StockProjectionSyncResult.MISSING;
        }
        if (rawResult == INVALID_STORED_STOCK) {
            throw unavailable(
                    "Redis stock projection contains invalid stock",
                    new IllegalStateException("Stored stock is invalid")
            );
        }
        if (rawResult == INVALID_STORED_REVISION) {
            throw unavailable(
                    "Redis stock projection contains invalid revision",
                    new IllegalStateException("Stored revision is invalid")
            );
        }
        throw unavailable(
                "Redis stock projection returned an unknown result: " + rawResult,
                new IllegalStateException("Unexpected synchronization Lua result")
        );
    }

    private StockProjectionSyncUnavailableException unavailable(
            String message,
            RuntimeException cause
    ) {
        return new StockProjectionSyncUnavailableException(message, cause);
    }
}
