package com.flashsale.inventory.application.port;

import com.flashsale.inventory.application.DurableStockDecrementResult;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;

/**
 * Application boundary for one committed, authoritative PostgreSQL stock decrement.
 */
public interface DurableStockDecrementPort {

    DurableStockDecrementResult decrement(
            ProductId productId,
            SaleId saleId,
            int quantity
    );
}
