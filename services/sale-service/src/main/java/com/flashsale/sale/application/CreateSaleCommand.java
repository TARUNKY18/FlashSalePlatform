package com.flashsale.sale.application;

import com.flashsale.sale.domain.vo.ProductId;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Application-layer command for {@code ScheduleSale}. Distinct from
 * {@code api.dto.CreateSaleRequest} (the wire-format DTO) — this is what the
 * {@link SaleCommandService} use case actually operates on, keeping the web layer's
 * JSON shape decoupled from the domain's construction parameters.
 */
public record CreateSaleCommand(
        String name,
        ProductId productId,
        int totalStock,
        Instant saleStart,
        Instant saleEnd,
        ZoneId timezone
) {
}
