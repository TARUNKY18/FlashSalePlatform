package com.flashsale.order.infra.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataIdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecordJpaEntity, IdempotencyRecordJpaEntity.Key> {

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (
                user_id, idempotency_key, response_payload, http_status
            ) VALUES (
                :userId, :idempotencyKey, :responsePayload, :httpStatus
            )
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("userId") UUID userId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("responsePayload") String responsePayload,
            @Param("httpStatus") int httpStatus
    );

    Optional<IdempotencyRecordJpaEntity> findByUserIdAndIdempotencyKey(
            UUID userId,
            String idempotencyKey
    );
}
