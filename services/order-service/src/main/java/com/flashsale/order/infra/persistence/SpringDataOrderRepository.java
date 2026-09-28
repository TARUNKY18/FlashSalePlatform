package com.flashsale.order.infra.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrderRepository extends JpaRepository<OrderJpaEntity, UUID> {

    Optional<OrderJpaEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    boolean existsByReservationId(UUID reservationId);
}
