package com.flashsale.inventory.infra.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataProductRepository extends JpaRepository<ProductJpaEntity, UUID> {

    /**
     * Loads the complete aggregate in one repository operation so mapping never depends on
     * an open persistence session.
     */
    @Override
    @EntityGraph(attributePaths = "stockLevels")
    Optional<ProductJpaEntity> findById(UUID id);
}
