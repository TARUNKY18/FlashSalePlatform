package com.flashsale.inventory.application.port;

import com.flashsale.inventory.application.StockReleaseResult;
import com.flashsale.inventory.domain.vo.SaleId;

/**
 * Outbound boundary for best-effort Redis stock restoration on reservation expiry or release.
 */
public interface StockReleasePort {

    StockReleaseResult release(SaleId saleId, int quantity, int ceiling);
}
