package com.flashsale.sale.api.dto;

import com.flashsale.sale.domain.aggregate.FlashSale;

/**
 * Wire format for {@code FlashSale} responses, shared by
 * {@code POST /api/v1/sales} and {@code GET /api/v1/sales/{id}}.
 */
public record SaleResponse(
        String saleId,
        String name,
        String productId,
        int totalStock,
        String status,
        String saleStart,
        String saleEnd,
        String timezone,
        long version
) {

    public static SaleResponse from(FlashSale sale) {
        return new SaleResponse(
                sale.id().toString(),
                sale.name(),
                sale.productId().toString(),
                sale.totalStock(),
                sale.status().code(),
                sale.schedule().window().start().toString(),
                sale.schedule().window().end().toString(),
                sale.schedule().timezone().getId(),
                sale.version()
        );
    }
}
