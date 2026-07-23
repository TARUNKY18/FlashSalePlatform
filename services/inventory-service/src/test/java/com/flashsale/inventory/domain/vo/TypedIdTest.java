package com.flashsale.inventory.domain.vo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedIdTest {

    private static final UUID VALUE = UUID.fromString("3b47e425-927e-46fa-9a61-e45a8f06314f");

    @Test
    void createsTypedIdsFromUuidAndString() {
        assertEquals(VALUE, ProductId.of(VALUE).value());
        assertEquals(VALUE, SaleId.of(VALUE.toString()).value());
        assertEquals(VALUE, StockLevelId.of(VALUE).value());
    }

    @Test
    void rejectsNullValues() {
        assertThrows(NullPointerException.class, () -> new ProductId(null));
        assertThrows(NullPointerException.class, () -> new SaleId(null));
        assertThrows(NullPointerException.class, () -> new StockLevelId(null));
    }

    @Test
    void keepsDifferentIdentityTypesDistinct() {
        ProductId productId = ProductId.of(VALUE);
        SaleId saleId = SaleId.of(VALUE);

        assertNotEquals(productId, saleId);
    }

    @Test
    void generatesUniqueAggregateAndEntityIds() {
        assertNotEquals(ProductId.generate(), ProductId.generate());
        assertNotEquals(StockLevelId.generate(), StockLevelId.generate());
    }
}
