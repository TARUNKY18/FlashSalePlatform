package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.application.StockReleaseResult;
import com.flashsale.inventory.application.port.StockReleasePort;
import com.flashsale.inventory.application.port.StockReleaseUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

/**
 * Redis adapter for stock release via stock-release.lua.
 */
@Component
public class RedisStockReleaseAdapter implements StockReleasePort {

    private static final long SALE_ENDED = -2L;

    private final StockReleaseLuaExecutor luaExecutor;

    public RedisStockReleaseAdapter(StockReleaseLuaExecutor luaExecutor) {
        this.luaExecutor = luaExecutor;
    }

    @Override
    public StockReleaseResult release(SaleId saleId, int quantity, int ceiling) {
        final Long rawResult;
        try {
            rawResult = luaExecutor.execute(saleId, quantity, ceiling);
        } catch (DataAccessException | SerializationException ex) {
            throw new StockReleaseUnavailableException(
                    "Redis stock release failed with indeterminate outcome", ex);
        }

        if (rawResult == null) {
            throw new StockReleaseUnavailableException(
                    "Redis stock release returned null");
        }
        if (rawResult == SALE_ENDED) {
            return StockReleaseResult.SALE_ENDED;
        }
        if (rawResult >= 0) {
            return StockReleaseResult.RELEASED;
        }
        throw new StockReleaseUnavailableException(
                "Redis stock release returned unexpected result: " + rawResult);
    }
}
