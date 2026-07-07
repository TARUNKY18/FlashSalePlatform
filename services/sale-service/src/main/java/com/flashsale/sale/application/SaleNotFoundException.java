package com.flashsale.sale.application;

import com.flashsale.sale.domain.vo.SaleId;

/**
 * Thrown when a requested {@code FlashSale} does not exist. Mapped to {@code 404} by
 * {@code api.GlobalExceptionHandler}.
 */
public final class SaleNotFoundException extends RuntimeException {

    public SaleNotFoundException(SaleId saleId) {
        super("Sale not found: " + saleId);
    }
}
