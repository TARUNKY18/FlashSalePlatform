package com.flashsale.sale.application;

import com.flashsale.sale.domain.aggregate.FlashSale;
import com.flashsale.sale.infra.persistence.SaleRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: create a flash sale (US-001, FR-001).
 *
 * <p><b>Week 2 scope note:</b> only {@link #createSale} is exposed here. The
 * {@code FlashSale} aggregate already implements {@code activate}/{@code end}/{@code archive}
 * (needed for the Week 2 state-machine unit tests), but nothing in Week 2's approved scope
 * calls them through this service — there is no Scheduler and no
 * {@code PATCH /api/v1/sales/{id}/status} endpoint yet (both are later weeks per
 * Build-Plan.md). Application-layer command methods for those transitions will be added
 * when something actually invokes them, rather than building unreachable surface now.
 */
@Service
public class SaleCommandService {

    /**
     * TODO(auth-milestone): replace with the authenticated actor identity once the API
     * gateway / bearer-token auth is implemented (PROJECT_TRUTH.md — "Bearer-token auth at
     * gateway only"). This is a temporary Week 2 implementation decision, not permanent
     * architecture — locked explicitly for Week 2 only, pending the auth milestone.
     */
    private static final String SYSTEM_ACTOR = "SYSTEM";

    private final SaleRepository saleRepository;
    private final Clock clock;

    public SaleCommandService(SaleRepository saleRepository, Clock clock) {
        this.saleRepository = saleRepository;
        this.clock = clock;
    }

    @Transactional
    public FlashSale createSale(CreateSaleCommand command) {
        Instant now = clock.instant();

        FlashSale sale = FlashSale.schedule(
                command.name(),
                command.productId(),
                command.totalStock(),
                command.saleStart(),
                command.saleEnd(),
                command.timezone(),
                now
        );

        saleRepository.save(sale);

        // US-004 / FR-008: every status transition — including the initial SCHEDULED
        // entry — is recorded in the immutable audit log.
        saleRepository.appendStatusHistory(sale.id(), null, sale.status().code(), now, SYSTEM_ACTOR, null);

        return sale;
    }
}
