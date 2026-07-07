package com.flashsale.sale.domain.vo;

/**
 * Reason a sale transitioned {@code ACTIVE -> ENDED}.
 *
 * Values match the {@code end_reason} column convention documented in schema.sql
 * (flash_sales table comment) and DatabaseSchema.md §2.1.
 */
public enum EndReason {
    TIME_ELAPSED,
    STOCK_DEPLETED,
    ADMIN_FORCE
}
