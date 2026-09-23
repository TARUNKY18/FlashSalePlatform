package com.flashsale.order.infra.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOrderOutboxRepository
        extends JpaRepository<OrderOutboxJpaEntity, UUID> {}
