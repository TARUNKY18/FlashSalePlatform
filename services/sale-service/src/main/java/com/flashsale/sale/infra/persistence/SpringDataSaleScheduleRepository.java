package com.flashsale.sale.infra.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSaleScheduleRepository extends JpaRepository<SaleScheduleJpaEntity, UUID> {

    Optional<SaleScheduleJpaEntity> findBySaleId(UUID saleId);
}
