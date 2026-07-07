package com.flashsale.sale.domain.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed reference to a product owned by InventoryService.
 *
 * This is an <b>opaque reference</b> (DD-003 in DatabaseSchema.md) — SaleContext does not
 * know anything about the product beyond its identity. No cross-database foreign key exists;
 * this type exists purely to avoid passing a raw {@link UUID} where a specific identity is
 * expected (DomainModel.md §4.1).
 */
public record ProductId(UUID value) {

    public ProductId {
        Objects.requireNonNull(value, "ProductId must not be null");
    }

    public static ProductId of(String value) {
        return new ProductId(UUID.fromString(value));
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
