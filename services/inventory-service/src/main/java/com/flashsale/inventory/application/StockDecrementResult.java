package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Objects;

/**
 * Application-level outcome of one fully resolved stock-decrement attempt.
 */
public sealed interface StockDecrementResult
        permits StockDecrementResult.Decremented,
                StockDecrementResult.SoldOut {

    record Decremented(StockCount remainingStock) implements StockDecrementResult {

        public Decremented {
            Objects.requireNonNull(remainingStock, "remainingStock must not be null");
        }
    }

    record SoldOut() implements StockDecrementResult {
    }
}
