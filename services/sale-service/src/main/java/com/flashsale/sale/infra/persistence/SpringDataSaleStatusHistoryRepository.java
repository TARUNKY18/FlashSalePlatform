package com.flashsale.sale.infra.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSaleStatusHistoryRepository extends JpaRepository<SaleStatusHistoryJpaEntity, UUID> {
}
