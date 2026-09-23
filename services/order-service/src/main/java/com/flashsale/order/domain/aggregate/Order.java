package com.flashsale.order.domain.aggregate;

import com.flashsale.order.domain.entity.OutboxEvent;
import com.flashsale.order.domain.event.OrderCreated;
import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.OrderId;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.time.Instant;
import java.util.Objects;

/** Aggregate root for an order placed from a purchase intent. */
public final class Order {

    public sealed interface Status
            permits Status.Pending, Status.Confirmed, Status.Cancelled, Status.Expired {
        record Pending() implements Status {}

        record Confirmed(Instant confirmedAt) implements Status {
            public Confirmed {
                Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
            }
        }

        record Cancelled(Instant cancelledAt, String reason) implements Status {
            public Cancelled {
                Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
                requireText(reason, "reason");
            }
        }

        record Expired(Instant expiredAt) implements Status {
            public Expired {
                Objects.requireNonNull(expiredAt, "expiredAt must not be null");
            }
        }
    }

    private final OrderId id;
    private final PurchaseIntentId purchaseIntentId;
    private final UserId userId;
    private final SaleId saleId;
    private final Money amount;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final OutboxEvent outboxEvent;
    private Status status;
    private long version;

    private Order(
            OrderId id,
            PurchaseIntentId purchaseIntentId,
            UserId userId,
            SaleId saleId,
            Money amount,
            String idempotencyKey,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.purchaseIntentId = Objects.requireNonNull(
                purchaseIntentId, "purchaseIntentId must not be null"
        );
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.saleId = Objects.requireNonNull(saleId, "saleId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = new Status.Pending();
        this.version = 0L;
        this.outboxEvent = OutboxEvent.pending(
                OrderCreated.create(
                        id, purchaseIntentId, userId, saleId, amount, createdAt
                ),
                createdAt
        );
    }

    public static Order place(
            PurchaseIntentId purchaseIntentId,
            UserId userId,
            SaleId saleId,
            Money amount,
            String idempotencyKey,
            Instant createdAt
    ) {
        return new Order(
                OrderId.generate(), purchaseIntentId, userId, saleId,
                amount, idempotencyKey, createdAt
        );
    }

    public void confirm(Instant confirmedAt) {
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        requirePending("confirm");
        status = new Status.Confirmed(confirmedAt);
        version = Math.incrementExact(version);
    }

    public void cancel(String reason, Instant cancelledAt) {
        requireText(reason, "reason");
        Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
        requirePending("cancel");
        status = new Status.Cancelled(cancelledAt, reason);
        version = Math.incrementExact(version);
    }

    public void expire(Instant expiredAt) {
        Objects.requireNonNull(expiredAt, "expiredAt must not be null");
        requirePending("expire");
        status = new Status.Expired(expiredAt);
        version = Math.incrementExact(version);
    }

    private void requirePending(String command) {
        if (!(status instanceof Status.Pending)) {
            throw new IllegalStateException(
                    "Cannot " + command + " an order in status "
                            + status.getClass().getSimpleName()
            );
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public OrderId id() { return id; }
    public PurchaseIntentId purchaseIntentId() { return purchaseIntentId; }
    public UserId userId() { return userId; }
    public SaleId saleId() { return saleId; }
    public Money amount() { return amount; }
    public String idempotencyKey() { return idempotencyKey; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public long version() { return version; }
    public OutboxEvent outboxEvent() { return outboxEvent; }
}
