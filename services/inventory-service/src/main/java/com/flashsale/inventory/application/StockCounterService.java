package com.flashsale.inventory.application;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one stock-decrement attempt through application ports.
 *
 * <p>The Product aggregate remains the authority for allocation ownership and quantity
 * bounds. This service does not implement fallback, retries, pre-warming, or persistence
 * updates after Redis execution.
 */
@Service
public class StockCounterService {

    private static final long CACHE_MISS = -2L;
    private static final long SOLD_OUT = -1L;

    private final ProductRepository productRepository;
    private final StockDecrementPort stockDecrementPort;

    public StockCounterService(
            ProductRepository productRepository,
            StockDecrementPort stockDecrementPort
    ) {
        this.productRepository = productRepository;
        this.stockDecrementPort = stockDecrementPort;
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

        Long rawResult = stockDecrementPort.decrement(saleId, quantity);
        if (rawResult == null) {
            throw new IllegalStateException("Stock decrement port returned null");
        }
        if (rawResult == CACHE_MISS) {
            return new StockDecrementResult.CacheMiss();
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
}
