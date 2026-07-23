package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Objects;

/**
 * Application-level outcome of one Redis stock-decrement attempt.
 *
 * <p>A cache miss is exposed as an outcome because PostgreSQL fallback and Redis re-warming
 * are intentionally outside the current implementation slice.
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
