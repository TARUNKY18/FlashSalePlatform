package com.flashsale.sale.domain.exception;

/**
 * Thrown when {@code FlashSale.schedule(...)} is given invalid creation parameters.
 *
 * <p>Distinct from illegal <i>state transitions</i> (which throw plain
 * {@link IllegalStateException} per Build-Plan.md Week 2 Definition of Done). This is a
 * creation-time validation failure, and carries a specific {@link ErrorCode} so the API
 * layer can map to the exact error bodies specified in PRD-FlashSalePlatform.md §7.1
 * (EC-002, EC-003, EC-004) without parsing exception message strings.
 */
public final class SaleCreationException extends IllegalArgumentException {

    public enum ErrorCode {
        /** EC-002 — saleStart is not in the future at creation time. */
        INVALID_SALE_START,
        /** EC-003 — totalStock is not greater than zero. */
        INVALID_STOCK,
        /** EC-004 — saleEnd is not after saleStart. */
        INVALID_SALE_WINDOW
    }

    private final ErrorCode errorCode;

    private SaleCreationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static SaleCreationException invalidSaleStart() {
        return new SaleCreationException(ErrorCode.INVALID_SALE_START, "saleStart must be in the future.");
    }

    public static SaleCreationException invalidStock() {
        return new SaleCreationException(ErrorCode.INVALID_STOCK, "totalStock must be greater than zero.");
    }

    public static SaleCreationException invalidSaleWindow() {
        return new SaleCreationException(ErrorCode.INVALID_SALE_WINDOW, "saleEnd must be after saleStart.");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
