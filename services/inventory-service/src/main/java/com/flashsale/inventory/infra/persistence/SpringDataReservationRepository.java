package com.flashsale.inventory.infra.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataReservationRepository extends JpaRepository<ReservationJpaEntity, UUID> {

    Optional<ReservationJpaEntity> findByIdempotencyKey(String key);

    @Query("SELECT r FROM ReservationJpaEntity r WHERE r.status = 'PENDING' AND r.expiresAt <= :now")
    List<ReservationJpaEntity> findExpiredPending(@Param("now") Instant now);
}
