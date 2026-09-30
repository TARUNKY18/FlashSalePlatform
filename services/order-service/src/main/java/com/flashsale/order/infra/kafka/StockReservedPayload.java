package com.flashsale.order.infra.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Wire shape of the {@code StockReserved} envelope payload on {@code inventory-events}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockReservedPayload(
        String reservationId,
        String saleId,
        String productId,
        String userId,
        int quantity,
        int remainingStock,
        String expiresAt
) {
}
