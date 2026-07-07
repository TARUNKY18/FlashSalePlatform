package com.flashsale.sale.application;

import com.flashsale.sale.domain.aggregate.FlashSale;
import com.flashsale.sale.domain.vo.SaleId;
import com.flashsale.sale.infra.persistence.SaleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: read a flash sale by id (partial support for US-001's "readable immediately
 * after creation" criterion — via {@code GET /api/v1/sales/{id}}, not the Redis-backed
 * {@code /active} hot path, which is Week 7 scope).
 */
@Service
@Transactional(readOnly = true)
public class SaleQueryService {

    private final SaleRepository saleRepository;

    public SaleQueryService(SaleRepository saleRepository) {
        this.saleRepository = saleRepository;
    }

    public FlashSale getById(SaleId saleId) {
        return saleRepository.findById(saleId)
                .orElseThrow(() -> new SaleNotFoundException(saleId));
    }
}
