package com.flashsale.inventory.application.port;

import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;

/**
 * Application boundary for safely restoring a missing primary stock counter.
 *
 * <p>The durable remaining stock may be written only when the counter is absent. An
 * implementation must never replace a counter that another request has already restored
 * or decremented.
 */
public interface StockRewarmPort {

    void rewarmIfAbsent(SaleId saleId, StockCount remainingStock);
}
