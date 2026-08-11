package com.flashsale.inventory.infra.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataReservationRepository extends JpaRepository<ReservationJpaEntity, UUID> {
}
