package com.flashsale.order.infra.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SpringDataOrderOutboxRepository
        extends JpaRepository<OrderOutboxJpaEntity, UUID> {

    @Query(value = """
            SELECT * FROM order_outbox
            WHERE published = FALSE
            ORDER BY created_at ASC
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OrderOutboxJpaEntity> lockNextUnpublishedBatch();
}
