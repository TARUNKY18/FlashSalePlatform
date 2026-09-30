package com.flashsale.order.domain.vo;

import java.time.Instant;
import java.util.Objects;

/** OrderContext's translation of an upstream stock hold. */
public record PurchaseIntent(
        PurchaseIntentId purchaseIntentId,
        UserId userId,
        SaleId saleId,
        int quantity,
        Instant validUntil
) {

    public PurchaseIntent {
        Objects.requireNonNull(purchaseIntentId, "purchaseIntentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(validUntil, "validUntil must not be null");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    /** Valid only strictly before {@code validUntil}. */
    public boolean isStillValid(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return now.isBefore(validUntil);
    }
}
