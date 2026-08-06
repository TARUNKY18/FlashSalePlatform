package com.flashsale.inventory.domain.vo;

/**
 * Strictly positive reservation quantity.
 */
public record Quantity(int value) {

    public Quantity {
        if (value < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1: " + value);
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }

    public static Quantity one() {
        return new Quantity(1);
    }
}
