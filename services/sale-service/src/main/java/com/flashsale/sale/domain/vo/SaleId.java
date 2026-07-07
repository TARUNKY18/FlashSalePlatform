package com.flashsale.sale.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identity for the {@code FlashSale} aggregate root.
 *
 * Per DomainModel.md §4.1: a raw {@link UUID} is never passed across a method
 * boundary where a typed ID is expected. This eliminates an entire class of bug
 * where one aggregate's identifier is accidentally passed where another's is required.
 */
public record SaleId(UUID value) {

    public SaleId {
        Objects.requireNonNull(value, "SaleId must not be null");
    }

    public static SaleId generate() {
        return new SaleId(UUID.randomUUID());
    }

    public static SaleId of(String value) {
        return new SaleId(UUID.fromString(value));
    }

    public static SaleId of(UUID value) {
        return new SaleId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
