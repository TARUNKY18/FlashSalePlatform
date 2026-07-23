package com.flashsale.inventory.infra.redis;

import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.domain.vo.SaleId;
import org.springframework.stereotype.Component;

/**
 * Redis implementation of the stock-decrement outbound port.
 *
 * <p>All Redis key construction, serialization, and Lua execution remain encapsulated in
 * {@link StockDecrementLuaExecutor}. This adapter only connects that infrastructure to the
 * Redis-neutral port.
 */
@Component
public class RedisStockDecrementAdapter implements StockDecrementPort {

    private final StockDecrementLuaExecutor luaExecutor;

    public RedisStockDecrementAdapter(StockDecrementLuaExecutor luaExecutor) {
        this.luaExecutor = luaExecutor;
    }

    @Override
    public Long decrement(SaleId saleId, int quantity) {
        return luaExecutor.execute(saleId, quantity);
    }
}
