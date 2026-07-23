package com.flashsale.inventory.domain.vo;

/**
 * Non-negative quantity of stock.
 */
public record StockCount(int value) {

    public StockCount {
        if (value < 0) {
            throw new IllegalArgumentException("StockCount cannot be negative: " + value);
        }
    }

    public static StockCount of(int value) {
        return new StockCount(value);
    }

    public static StockCount zero() {
        return new StockCount(0);
    }

    public boolean isAvailable() {
        return value > 0;
    }

    public boolean isSoldOut() {
        return value == 0;
    }

    public boolean canDecrement(int quantity) {
        return quantity > 0 && value >= quantity;
    }

    public StockCount decrement(int quantity) {
        requirePositive(quantity);
        if (quantity > value) {
            throw new IllegalArgumentException(
                    "Cannot decrement " + quantity + " from stock count " + value
            );
        }
        return new StockCount(value - quantity);
    }

    public StockCount increment(int quantity) {
        requirePositive(quantity);
        return new StockCount(Math.addExact(value, quantity));
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Stock quantity must be positive: " + quantity);
        }
    }
}
