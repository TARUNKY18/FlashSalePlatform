package com.flashsale.sale.domain.event;

import com.flashsale.sale.domain.vo.EndReason;
import com.flashsale.sale.domain.vo.SaleId;
import java.time.Instant;
import java.util.Objects;

/**
 * Raised on {@code ACTIVE -> ENDED} (DomainModel.md §2.1, §9).
 *
 * <p>Not yet published to Kafka in Week 2 — see {@link SaleScheduled} javadoc.
 */
public record SaleEnded(SaleId saleId, EndReason reason, Instant occurredAt) {
    public SaleEnded {
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
