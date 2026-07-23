package com.flashsale.inventory.application.port;

import com.flashsale.inventory.domain.vo.SaleId;

/**
 * Outbound boundary for atomically decrementing a sale's stock counter.
 *
 * <p>The contract intentionally exposes no Redis or Lua types. Interpreting the raw result
 * belongs to a later application-layer service.
 */
public interface StockDecrementPort {

    Long decrement(SaleId saleId, int quantity);
}
