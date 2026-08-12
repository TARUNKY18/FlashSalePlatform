package com.flashsale.inventory.infra.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SpringDataInventoryOutboxRepository
        extends JpaRepository<InventoryOutboxJpaEntity, UUID> {

    @Query(value = """
            SELECT * FROM inventory_outbox
            WHERE published = FALSE
            ORDER BY created_at ASC
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<InventoryOutboxJpaEntity> lockNextUnpublishedBatch();
}
