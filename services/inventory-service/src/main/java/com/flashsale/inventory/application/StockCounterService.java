package com.flashsale.inventory.application;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.application.port.StockDecrementUnavailableException;
import com.flashsale.inventory.application.port.StockFallbackPort;
import com.flashsale.inventory.application.port.StockRewarmPort;
import com.flashsale.inventory.application.port.StockRewarmUnavailableException;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one stock-decrement attempt through application ports.
 *
 * <p>The Product aggregate remains the authority for allocation ownership and quantity
 * bounds. A cache miss or unavailable primary counter delegates once to the durable
 * fallback. A successful fallback is used to safely restore a missing primary counter.
 * This service does not retry or pre-warm Redis.
 */
@Service
public class StockCounterService {

    private static final long CACHE_MISS = -2L;
    private static final long SOLD_OUT = -1L;

    private final ProductRepository productRepository;
    private final StockDecrementPort stockDecrementPort;
    private final StockFallbackPort stockFallbackPort;
    private final StockRewarmPort stockRewarmPort;

    public StockCounterService(
            ProductRepository productRepository,
            StockDecrementPort stockDecrementPort,
            StockFallbackPort stockFallbackPort,
            StockRewarmPort stockRewarmPort
    ) {
        this.productRepository = productRepository;
        this.stockDecrementPort = stockDecrementPort;
        this.stockFallbackPort = stockFallbackPort;
        this.stockRewarmPort = stockRewarmPort;
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

        Long rawResult;
        try {
            rawResult = stockDecrementPort.decrement(saleId, quantity);
        } catch (StockDecrementUnavailableException exception) {
            return fallback(productId, saleId, quantity);
        }
        if (rawResult == null) {
            throw new IllegalStateException("Stock decrement port returned null");
        }
        if (rawResult == CACHE_MISS) {
            return fallback(productId, saleId, quantity);
        }
        if (rawResult == SOLD_OUT) {
            return new StockDecrementResult.SoldOut();
        }
        if (rawResult < 0) {
            throw new IllegalStateException(
                    "Unexpected stock decrement result: " + rawResult
            );
        }

        try {
            return new StockDecrementResult.Decremented(
                    StockCount.of(Math.toIntExact(rawResult))
            );
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "Stock decrement result exceeds supported range: " + rawResult,
                    exception
            );
        }
    }

    private StockDecrementResult fallback(
            ProductId productId,
            SaleId saleId,
            int quantity
    ) {
        Optional<StockCount> remainingStock = stockFallbackPort.decrement(
                productId,
                saleId,
                quantity
        );
        if (remainingStock == null) {
            throw new IllegalStateException("Stock fallback port returned null");
        }
        if (remainingStock.isEmpty()) {
            return new StockDecrementResult.SoldOut();
        }

        StockCount durableRemainingStock = remainingStock.orElseThrow();
        try {
            stockRewarmPort.rewarmIfAbsent(saleId, durableRemainingStock);
        } catch (StockRewarmUnavailableException exception) {
            // The authoritative PostgreSQL decrement has already succeeded. Re-warming is
            // best-effort and cannot turn that committed decrement into a failed result.
        }
        return new StockDecrementResult.Decremented(durableRemainingStock);
    }
}
