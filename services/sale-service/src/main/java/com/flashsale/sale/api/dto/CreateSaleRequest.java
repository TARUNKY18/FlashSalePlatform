package com.flashsale.sale.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Wire format for {@code POST /api/v1/sales} (FR-001).
 *
 * <p>Bean Validation here only enforces <i>structural</i> required-ness (a field is
 * missing/blank entirely) — {@code 400} in that case comes from Spring's standard
 * {@code MethodArgumentNotValidException} handling. <i>Business-rule</i> validation
 * (totalStock &gt; 0, saleStart in the future, saleEnd after saleStart — EC-002/003/004)
 * deliberately happens in {@code FlashSale.schedule(...)}, not here, so those specific PRD
 * error codes come from one place: the domain layer.
 *
 * @param timezone optional; defaults to {@code "UTC"} if omitted, per schema.sql's
 *                 {@code sale_schedules.timezone} column default.
 */
public record CreateSaleRequest(
        @NotBlank String name,
        @NotBlank String productId,
        @NotNull Integer totalStock,
        @NotNull Instant saleStart,
        @NotNull Instant saleEnd,
        String timezone
) {
}
