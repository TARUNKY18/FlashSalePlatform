package com.flashsale.order.application;

import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.util.Objects;

/** Validated inputs required to place an Order. */
public record PlaceOrderCommand(
        PurchaseIntentId purchaseIntentId,
        UserId userId,
        SaleId saleId,
        Money amount,
        String idempotencyKey
) {

    public PlaceOrderCommand {
        Objects.requireNonNull(purchaseIntentId, "purchaseIntentId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
