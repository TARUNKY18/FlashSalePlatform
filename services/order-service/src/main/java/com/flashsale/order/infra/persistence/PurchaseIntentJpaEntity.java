package com.flashsale.order.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** JPA representation of a received purchase intent; written only by native insert. */
@Entity
@Table(name = "purchase_intents")
public class PurchaseIntentJpaEntity {

    @Id
    @Column(name = "purchase_intent_id", nullable = false, updatable = false)
    private UUID purchaseIntentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "valid_until", nullable = false, updatable = false)
    private Instant validUntil;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected PurchaseIntentJpaEntity() {
    }
}
