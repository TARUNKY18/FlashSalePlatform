package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.aggregate.Reservation.Status;
import com.flashsale.inventory.domain.vo.OrderId;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.ReservationId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The only translation boundary between the Reservation domain and JPA models.
 */
@Component
public class ReservationPersistenceMapper {

    public ReservationJpaEntity toJpaEntity(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        return new ReservationJpaEntity(
                reservation.id().value(),
                reservation.userId().value(),
                reservation.saleId().value(),
                reservation.productId().value(),
                reservation.status().getClass().getSimpleName().toUpperCase(),
                reservation.quantity().value(),
                reservation.expiry().expiresAt(),
                reservation.idempotencyKey(),
                reservation.orderId() != null ? reservation.orderId().value() : null,
                reservation.version()
        );
    }

    public Reservation toDomain(ReservationJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        Status status = switch (entity.getStatus()) {
            case "PENDING"   -> new Status.Pending();
            case "CONFIRMED" -> new Status.Confirmed();
            case "EXPIRED"   -> new Status.Expired();
            case "RELEASED"  -> new Status.Released();
            default -> throw new IllegalStateException(
                    "Unknown reservation status: " + entity.getStatus()
            );
        };
        OrderId orderId = entity.getOrderId() != null
                ? new OrderId(entity.getOrderId())
                : null;
        return Reservation.reconstitute(
                ReservationId.of(entity.getId()),
                UserId.of(entity.getUserId()),
                SaleId.of(entity.getSaleId()),
                ProductId.of(entity.getProductId()),
                Quantity.of(entity.getQuantity()),
                new ReservationExpiry(entity.getExpiresAt()),
                status,
                orderId,
                entity.getVersion(),
                entity.getIdempotencyKey()
        );
    }
}
