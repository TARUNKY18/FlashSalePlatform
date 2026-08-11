package com.flashsale.inventory.domain.aggregate;

import com.flashsale.inventory.domain.vo.OrderId;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.ReservationId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root for a timed stock hold for one user and one sale.
 */
public final class Reservation {

    public sealed interface Status
            permits Status.Pending, Status.Confirmed, Status.Expired, Status.Released {
        record Pending()   implements Status {}
        record Confirmed() implements Status {}
        record Expired()   implements Status {}
        record Released()  implements Status {}
    }

    private final ReservationId id;
    private final UserId userId;
    private final SaleId saleId;
    private final ProductId productId;
    private final Quantity quantity;
    private final ReservationExpiry expiry;
    private Status status;
    private OrderId orderId;
    private long version;
    private final String idempotencyKey;

    private Reservation(
            ReservationId id,
            UserId userId,
            SaleId saleId,
            ProductId productId,
            Quantity quantity,
            ReservationExpiry expiry,
            Status status,
            OrderId orderId,
            long version,
            String idempotencyKey
    ) {
        this.id             = Objects.requireNonNull(id,        "id must not be null");
        this.userId         = Objects.requireNonNull(userId,    "userId must not be null");
        this.saleId         = Objects.requireNonNull(saleId,    "saleId must not be null");
        this.productId      = Objects.requireNonNull(productId, "productId must not be null");
        this.quantity       = Objects.requireNonNull(quantity,  "quantity must not be null");
        this.expiry         = Objects.requireNonNull(expiry,    "expiry must not be null");
        this.status         = Objects.requireNonNull(status,    "status must not be null");
        this.orderId        = orderId; // nullable until confirmed
        this.idempotencyKey = idempotencyKey; // nullable for non-REST paths
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
    }

    public static Reservation create(
            UserId userId,
            SaleId saleId,
            ProductId productId,
            Quantity quantity,
            ReservationExpiry expiry,
            Instant now
    ) {
        return create(userId, saleId, productId, quantity, expiry, now, null);
    }

    public static Reservation create(
            UserId userId,
            SaleId saleId,
            ProductId productId,
            Quantity quantity,
            ReservationExpiry expiry,
            Instant now,
            String idempotencyKey
    ) {
        Objects.requireNonNull(now, "now must not be null");
        if (expiry.isExpired(now)) {
            throw new IllegalArgumentException(
                    "Cannot create a reservation with an already-expired expiry"
            );
        }
        return new Reservation(
                ReservationId.generate(), userId, saleId, productId,
                quantity, expiry, new Status.Pending(), null, 0L, idempotencyKey
        );
    }

    /**
     * Recreates a persisted {@code Reservation} without re-validating expiry against now.
     */
    public static Reservation reconstitute(
            ReservationId id,
            UserId userId,
            SaleId saleId,
            ProductId productId,
            Quantity quantity,
            ReservationExpiry expiry,
            Status status,
            OrderId orderId,
            long version,
            String idempotencyKey
    ) {
        return new Reservation(
                id, userId, saleId, productId, quantity, expiry, status, orderId, version,
                idempotencyKey
        );
    }

    /**
     * Transitions PENDING to CONFIRMED on order placement.
     */
    public void confirm(OrderId orderId) {
        Objects.requireNonNull(orderId, "orderId must not be null");
        requireStatus(Status.Pending.class, "confirm");
        this.orderId = orderId;
        this.status  = new Status.Confirmed();
        this.version = Math.incrementExact(version);
    }

    /**
     * Transitions PENDING to EXPIRED when the TTL elapses.
     */
    public void expire() {
        requireStatus(Status.Pending.class, "expire");
        this.status  = new Status.Expired();
        this.version = Math.incrementExact(version);
    }

    /**
     * Transitions PENDING to RELEASED on user cancel or saga compensation.
     */
    public void release(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        requireStatus(Status.Pending.class, "release");
        this.status  = new Status.Released();
        this.version = Math.incrementExact(version);
    }

    private void requireStatus(Class<? extends Status> expected, String command) {
        if (!expected.isInstance(status)) {
            throw new IllegalStateException(
                    "Cannot " + command + " a reservation in status "
                            + status.getClass().getSimpleName()
            );
        }
    }

    public ReservationId     id()               { return id; }
    public UserId            userId()           { return userId; }
    public SaleId            saleId()           { return saleId; }
    public ProductId         productId()        { return productId; }
    public Quantity          quantity()         { return quantity; }
    public ReservationExpiry expiry()           { return expiry; }
    public Status            status()           { return status; }
    public OrderId           orderId()          { return orderId; }
    public long              version()          { return version; }
    public String            idempotencyKey()   { return idempotencyKey; }
}
