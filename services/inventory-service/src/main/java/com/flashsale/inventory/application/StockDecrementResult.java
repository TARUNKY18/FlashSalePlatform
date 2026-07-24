package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Objects;

/**
 * Application-level outcome of one stock-decrement attempt.
 *
 * <p>The cache-miss variant remains part of the established result vocabulary, while
 * StockCounterService now resolves cache misses through the durable fallback before
 * returning to its caller.
 */
public sealed interface StockDecrementResult
        permits StockDecrementResult.Decremented,
                StockDecrementResult.SoldOut,
                StockDecrementResult.CacheMiss {

    record Decremented(StockCount remainingStock) implements StockDecrementResult {

        public Decremented {
            Objects.requireNonNull(remainingStock, "remainingStock must not be null");
        }
    }

    record SoldOut() implements StockDecrementResult {
    }

    record CacheMiss() implements StockDecrementResult {
    }
}
