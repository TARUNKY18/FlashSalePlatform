package com.flashsale.inventory.application.port;

import com.flashsale.inventory.application.PreWarmStockResult;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.time.Duration;

/**
 * Outbound boundary for revision-fenced atomic initialization of the stock projection pair.
 */
public interface StockPreWarmPort {

    PreWarmStockResult preWarm(SaleId saleId, StockCount stock, long revision, Duration ttl);
}
