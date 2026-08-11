package com.flashsale.inventory.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA representation of the Reservation aggregate root.
 *
 * <p>This class contains persistence structure only. Domain rules remain in
 * {@code com.flashsale.inventory.domain}.
 */
@Entity
@Table(name = "reservations")
public class ReservationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "order_id")
    private UUID orderId;

    @Version
    @Column(nullable = false)
    private long version;

    /**
     * Required by JPA.
     */
    protected ReservationJpaEntity() {
    }

    ReservationJpaEntity(
            UUID id,
            UUID userId,
            UUID saleId,
            UUID productId,
            String status,
            int quantity,
            Instant expiresAt,
            String idempotencyKey,
            UUID orderId,
            long version
    ) {
        this.id             = Objects.requireNonNull(id,        "id must not be null");
        this.userId         = Objects.requireNonNull(userId,    "userId must not be null");
        this.saleId         = Objects.requireNonNull(saleId,    "saleId must not be null");
        this.productId      = Objects.requireNonNull(productId, "productId must not be null");
        this.status         = Objects.requireNonNull(status,    "status must not be null");
        this.expiresAt      = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.idempotencyKey = idempotencyKey;
        this.quantity       = quantity;
        this.orderId        = orderId;
        this.version        = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSaleId() {
        return saleId;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getStatus() {
        return status;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public long getVersion() {
        return version;
    }

    void updateStatus(String status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    void updateOrderId(UUID orderId) {
        this.orderId = orderId;
    }
}
