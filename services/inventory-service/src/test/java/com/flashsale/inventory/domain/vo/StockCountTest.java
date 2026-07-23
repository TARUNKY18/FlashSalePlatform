package com.flashsale.inventory.domain.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StockCountTest {

    @Test
    void rejectsNegativeStock() {
        assertThrows(IllegalArgumentException.class, () -> StockCount.of(-1));
    }

    @Test
    void reportsAvailabilityAndSoldOutState() {
        StockCount soldOut = StockCount.zero();
        StockCount available = StockCount.of(1);

        assertTrue(soldOut.isSoldOut());
        assertFalse(soldOut.isAvailable());
        assertTrue(available.isAvailable());
        assertFalse(available.isSoldOut());
    }

    @Test
    void decrementsWithoutMutatingOriginalValue() {
        StockCount original = StockCount.of(10);

        StockCount decremented = original.decrement(4);

        assertEquals(10, original.value());
        assertEquals(6, decremented.value());
    }

    @Test
    void rejectsDecrementBeyondAvailableStock() {
        StockCount stock = StockCount.of(3);

        assertFalse(stock.canDecrement(4));
        assertThrows(IllegalArgumentException.class, () -> stock.decrement(4));
    }

    @Test
    void onlyPositiveQuantitiesCanBeApplied() {
        StockCount stock = StockCount.of(3);

        assertFalse(stock.canDecrement(0));
        assertFalse(stock.canDecrement(-1));
        assertThrows(IllegalArgumentException.class, () -> stock.decrement(0));
        assertThrows(IllegalArgumentException.class, () -> stock.increment(-1));
    }

    @Test
    void incrementsWithoutMutatingOriginalValue() {
        StockCount original = StockCount.of(5);

        StockCount incremented = original.increment(2);

        assertEquals(5, original.value());
        assertEquals(7, incremented.value());
    }

    @Test
    void rejectsIntegerOverflowWhenIncrementing() {
        StockCount maximum = StockCount.of(Integer.MAX_VALUE);

        assertThrows(ArithmeticException.class, () -> maximum.increment(1));
    }
}
