package com.flashsale.sale.domain.vo;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The sale's time window. Owning aggregate: {@code FlashSale}.
 *
 * Invariant: {@code end} must be strictly after {@code start}; duration must be > 0.
 * Verbatim from DomainModel.md §4.2 — this is documented architecture, not invented here.
 */
public record SaleWindow(Instant start, Instant end) {

    public SaleWindow {
        Objects.requireNonNull(start, "SaleWindow start must not be null");
        Objects.requireNonNull(end, "SaleWindow end must not be null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("SaleWindow end must be after start");
        }
    }

    public boolean isOpen(Instant now) {
        return !now.isBefore(start) && now.isBefore(end);
    }

    public boolean isUpcoming(Instant now) {
        return now.isBefore(start);
    }

    public boolean hasPassed(Instant now) {
        return !now.isBefore(end);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
