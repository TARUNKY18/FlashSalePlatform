package com.flashsale.inventory.infra.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataProductRepository extends JpaRepository<ProductJpaEntity, UUID> {

    /**
     * Loads the complete aggregate in one repository operation so mapping never depends on
     * an open persistence session.
     */
    @Override
    @EntityGraph(attributePaths = "stockLevels")
    Optional<ProductJpaEntity> findById(UUID id);

    /**
     * Locks the aggregate root so every PostgreSQL fallback for one Product is serialized.
     * Owned StockLevels are loaded inside the surrounding transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from ProductJpaEntity product where product.id = :id")
    Optional<ProductJpaEntity> findByIdForUpdate(@Param("id") UUID id);
}
