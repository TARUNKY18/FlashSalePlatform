package com.flashsale.inventory.application.port;

import com.flashsale.inventory.application.StockProjectionSyncResult;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;

/**
 * Application boundary for revision-fenced synchronization of the Redis stock projection.
 */
public interface StockProjectionSyncPort {

    StockProjectionSyncResult synchronize(
            SaleId saleId,
            StockCount durableStock,
            long durableRevision
    );
}
