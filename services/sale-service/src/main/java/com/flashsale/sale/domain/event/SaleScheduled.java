package com.flashsale.sale.domain.event;

import com.flashsale.sale.domain.vo.ProductId;
import com.flashsale.sale.domain.vo.SaleId;
import com.flashsale.sale.domain.vo.SaleWindow;
import java.time.Instant;
import java.util.Objects;

/**
 * Raised when a {@code FlashSale} is created (DomainModel.md §2.1, §9).
 *
 * <p><b>Week 2 scope note:</b> this event is raised and held by the aggregate
 * ({@link com.flashsale.sale.domain.aggregate.FlashSale#pullDomainEvents()}) but is not
 * yet published to Kafka. Publishing to {@code sale-events} (partition key {@code saleId})
 * is Week 6 scope (Kafka wiring) per Build-Plan.md. Defining the event now avoids having
 * to touch the aggregate again when the outbox/publisher is introduced.
 */
public record SaleScheduled(
        SaleId saleId,
        ProductId productId,
        String name,
        int totalStock,
        SaleWindow window,
        Instant occurredAt
) {
    public SaleScheduled {
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
