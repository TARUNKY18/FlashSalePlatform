package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.ReservationId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An Inventory application event awaiting durable Kafka publication. */
public sealed interface InventoryEvent
        permits InventoryEvent.StockReserved, InventoryEvent.ReservationExpired {

    String EVENT_VERSION = "1.0";
    String AGGREGATE_TYPE = "Reservation";

    UUID eventId();

    Instant occurredAt();

    ReservationId reservationId();

    default ReservationId aggregateId() {
        return reservationId();
    }

    default String eventVersion() {
        return EVENT_VERSION;
    }

    default String aggregateType() {
        return AGGREGATE_TYPE;
    }

    String eventType();

    record StockReserved(
            UUID eventId,
            Instant occurredAt,
            ReservationId reservationId,
            SaleId saleId,
            ProductId productId,
            UserId userId,
            int quantity,
            int remainingStock,
            Instant expiresAt
    ) implements InventoryEvent {

        public StockReserved {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            Objects.requireNonNull(reservationId, "reservationId must not be null");
            Objects.requireNonNull(saleId, "saleId must not be null");
            Objects.requireNonNull(productId, "productId must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be positive");
            }
            if (remainingStock < 0) {
                throw new IllegalArgumentException("remainingStock must not be negative");
            }
        }

        @Override
        public String eventType() {
            return "StockReserved";
        }
    }

    record ReservationExpired(
            UUID eventId,
            Instant occurredAt,
            ReservationId reservationId,
            SaleId saleId,
            ProductId productId,
            UserId userId,
            int quantity,
            Instant expiredAt
    ) implements InventoryEvent {

        public ReservationExpired {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(occurredAt, "occurredAt must not be null");
            Objects.requireNonNull(reservationId, "reservationId must not be null");
            Objects.requireNonNull(saleId, "saleId must not be null");
            Objects.requireNonNull(productId, "productId must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(expiredAt, "expiredAt must not be null");
            if (quantity < 1) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }

        @Override
        public String eventType() {
            return "ReservationExpired";
        }
    }
}
