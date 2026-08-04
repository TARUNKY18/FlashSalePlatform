package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Objects;

/**
 * Committed PostgreSQL outcome of one authoritative stock-decrement attempt.
 */
public sealed interface DurableStockDecrementResult
        permits DurableStockDecrementResult.Decremented,
                DurableStockDecrementResult.Insufficient {

    record Decremented(
            StockCount remainingStock,
            long revision
    ) implements DurableStockDecrementResult {

        public Decremented {
            Objects.requireNonNull(remainingStock, "remainingStock must not be null");
            requireValidRevision(revision);
        }
    }

    record Insufficient(
            StockCount currentStock,
            long revision
    ) implements DurableStockDecrementResult {

        public Insufficient {
            Objects.requireNonNull(currentStock, "currentStock must not be null");
            requireValidRevision(revision);
        }
    }

    private static void requireValidRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }
}
