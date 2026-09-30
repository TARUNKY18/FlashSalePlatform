package com.flashsale.order.infra.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPurchaseIntentRepository extends JpaRepository<PurchaseIntentJpaEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO purchase_intents (
                purchase_intent_id, user_id, sale_id, quantity, valid_until, received_at
            ) VALUES (
                :purchaseIntentId, :userId, :saleId, :quantity, :validUntil, :receivedAt
            )
            ON CONFLICT (purchase_intent_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("purchaseIntentId") UUID purchaseIntentId,
            @Param("userId") UUID userId,
            @Param("saleId") UUID saleId,
            @Param("quantity") int quantity,
            @Param("validUntil") Instant validUntil,
            @Param("receivedAt") Instant receivedAt
    );
}
