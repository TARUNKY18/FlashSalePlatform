package com.flashsale.inventory.application;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.DurableStockDecrementPort;
import com.flashsale.inventory.application.port.StockProjectionSyncPort;
import com.flashsale.inventory.application.port.StockProjectionSyncUnavailableException;
import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.application.port.StockDecrementUnavailableException;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one stock-decrement attempt through application ports.
 *
 * <p>The Product aggregate validates allocation ownership and quantity bounds. Redis is
 * attempted once as an atomic projection, while PostgreSQL is invoked once for every
 * recognized or indeterminate Redis outcome and exclusively determines the returned result.
 */
@Service
public class StockCounterService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StockCounterService.class);
    private static final long CACHE_MISS = -2L;
    private static final long SOLD_OUT = -1L;

    private final ProductRepository productRepository;
    private final StockDecrementPort stockDecrementPort;
    private final DurableStockDecrementPort durableStockDecrementPort;
    private final StockProjectionSyncPort stockProjectionSyncPort;

    public StockCounterService(
            ProductRepository productRepository,
            StockDecrementPort stockDecrementPort,
            DurableStockDecrementPort durableStockDecrementPort,
            StockProjectionSyncPort stockProjectionSyncPort
    ) {
        this.productRepository = productRepository;
        this.stockDecrementPort = stockDecrementPort;
        this.durableStockDecrementPort = durableStockDecrementPort;
        this.stockProjectionSyncPort = stockProjectionSyncPort;
    }

    public StockDecrementResult decrement(
            ProductId productId,
            SaleId saleId,
            int quantity
    ) {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(saleId, "saleId must not be null");

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Product not found: " + productId
                ));
        StockLevel stockLevel = product.stockLevelFor(saleId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Product " + productId + " has no StockLevel for sale " + saleId
                ));

        if (!stockLevel.totalAllocated().canDecrement(quantity)) {
            throw new IllegalArgumentException(
                    "quantity must be between 1 and the sale allocation of "
                            + stockLevel.totalAllocated().value()
            );
        }

        Long redisResult = null;
        try {
            redisResult = stockDecrementPort.decrement(saleId, quantity);
            validateRedisResult(redisResult);
        } catch (StockDecrementUnavailableException exception) {
            LOGGER.warn(
                    "Redis decrement outcome is indeterminate for product {} and sale {}; "
                            + "continuing to authoritative PostgreSQL",
                    productId,
                    saleId,
                    exception
            );
        }

        DurableStockDecrementResult durableResult =
                durableStockDecrementPort.decrement(productId, saleId, quantity);
        if (durableResult == null) {
            throw new IllegalStateException("Durable stock decrement port returned null");
        }

        warnIfProjectionDisagrees(productId, saleId, redisResult, durableResult);

        StockCount durableStock;
        long durableRevision;
        StockDecrementResult result;
        if (durableResult instanceof DurableStockDecrementResult.Decremented decremented) {
            durableStock = decremented.remainingStock();
            durableRevision = decremented.revision();
            result = new StockDecrementResult.Decremented(durableStock);
        } else if (durableResult
                instanceof DurableStockDecrementResult.Insufficient insufficient) {
            durableStock = insufficient.currentStock();
            durableRevision = insufficient.revision();
            result = new StockDecrementResult.SoldOut();
        } else {
            throw new IllegalStateException(
                    "Unexpected durable stock decrement result: " + durableResult
            );
        }

        synchronizeProjection(saleId, durableStock, durableRevision);
        return result;
    }

    private void validateRedisResult(Long rawResult) {
        if (rawResult == null) {
            throw new IllegalStateException("Stock decrement port returned null");
        }
        if (rawResult < CACHE_MISS) {
            throw new IllegalStateException(
                    "Unexpected stock decrement result: " + rawResult
            );
        }
        if (rawResult > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Stock decrement result exceeds supported range: " + rawResult
            );
        }
    }

    private void warnIfProjectionDisagrees(
            ProductId productId,
            SaleId saleId,
            Long redisResult,
            DurableStockDecrementResult durableResult
    ) {
        if (redisResult == null || redisResult == CACHE_MISS) {
            return;
        }

        if (durableResult instanceof DurableStockDecrementResult.Decremented decremented) {
            if (redisResult == SOLD_OUT) {
                LOGGER.warn(
                        "Redis projected sold-out but PostgreSQL committed a decrement "
                                + "for product {} and sale {}; durable remaining stock is {}",
                        productId,
                        saleId,
                        decremented.remainingStock().value()
                );
            } else if (redisResult >= 0
                    && redisResult.longValue() != decremented.remainingStock().value()) {
                LOGGER.warn(
                        "Redis and PostgreSQL remaining stock disagree for product {} "
                                + "and sale {}: Redis={}, PostgreSQL={}",
                        productId,
                        saleId,
                        redisResult,
                        decremented.remainingStock().value()
                );
            }
            return;
        }

        if (redisResult >= 0
                && durableResult instanceof DurableStockDecrementResult.Insufficient
                        insufficient) {
            LOGGER.warn(
                    "Redis projected a decrement but PostgreSQL found insufficient stock "
                            + "for product {} and sale {}; Redis remaining={}, "
                            + "PostgreSQL current={}",
                    productId,
                    saleId,
                    redisResult,
                    insufficient.currentStock().value()
            );
        }
    }

    private void synchronizeProjection(
            SaleId saleId,
            StockCount durableStock,
            long durableRevision
    ) {
        try {
            StockProjectionSyncResult syncResult = stockProjectionSyncPort.synchronize(
                    saleId,
                    durableStock,
                    durableRevision
            );
            if (syncResult == null) {
                LOGGER.warn(
                        "Redis projection synchronization returned no result for sale {}; "
                                + "preserving committed PostgreSQL outcome",
                        saleId
                );
            }
        } catch (StockProjectionSyncUnavailableException exception) {
            LOGGER.warn(
                    "Redis projection synchronization failed for sale {}; preserving "
                            + "committed PostgreSQL outcome",
                    saleId,
                    exception
            );
        }
    }
}
