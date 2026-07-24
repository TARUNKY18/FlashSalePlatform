package com.flashsale.inventory.application.port;

import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Optional;

/**
 * Application boundary for one authoritative durable stock decrement.
 *
 * <p>An empty result means the locked durable StockLevel has insufficient stock. A present
 * result contains the durable stock remaining after exactly one successful decrement.
 */
public interface StockFallbackPort {

    Optional<StockCount> decrement(ProductId productId, SaleId saleId, int quantity);
}
