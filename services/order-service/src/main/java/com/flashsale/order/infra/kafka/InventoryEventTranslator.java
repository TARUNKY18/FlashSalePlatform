package com.flashsale.order.infra.kafka;

import com.flashsale.order.domain.vo.PurchaseIntent;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer: translates Inventory's {@code StockReserved} payload
 * into OrderContext's {@link PurchaseIntent}. Every invalid input is reported
 * as {@link IllegalArgumentException}. Expiry is not checked here.
 */
@Component
public class InventoryEventTranslator {

    public PurchaseIntent translate(StockReservedPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.quantity() < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        return new PurchaseIntent(
                PurchaseIntentId.of(required(payload.reservationId(), "reservationId")),
                UserId.of(required(payload.userId(), "userId")),
                SaleId.of(required(payload.saleId(), "saleId")),
                payload.quantity(),
                instant(required(payload.expiresAt(), "expiresAt"))
        );
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt is not an ISO-8601 instant", e);
        }
    }
}
