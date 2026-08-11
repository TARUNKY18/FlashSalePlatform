package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.util.Objects;

/**
 * Command carrying all inputs for creating a reservation via the REST layer.
 */
public record CreateReservationCommand(
        String idempotencyKey,
        UserId userId,
        SaleId saleId,
        ProductId productId,
        Quantity quantity
) {

    public CreateReservationCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(userId,         "userId must not be null");
        Objects.requireNonNull(saleId,         "saleId must not be null");
        Objects.requireNonNull(productId,      "productId must not be null");
        Objects.requireNonNull(quantity,       "quantity must not be null");
    }
}
