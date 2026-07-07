package com.flashsale.sale.infra.persistence;

import com.flashsale.sale.domain.aggregate.FlashSale;
import com.flashsale.sale.domain.entity.SaleSchedule;
import com.flashsale.sale.domain.vo.EndReason;
import com.flashsale.sale.domain.vo.ProductId;
import com.flashsale.sale.domain.vo.SaleId;
import com.flashsale.sale.domain.vo.SaleStatus;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Maps the {@code FlashSale} aggregate to/from its {@code flash_sales} /
 * {@code sale_schedules} / {@code sale_status_history} JPA representations.
 *
 * <p>This is the one place the sealed {@code SaleStatus} interface is translated to and
 * from the {@code VARCHAR} column, per Build-Plan.md's Week 2 risk mitigation — JPA never
 * touches the sealed type directly.
 *
 * <p><b>Week 2 scope note:</b> {@link #save} only handles first-time inserts — the only
 * reachable path this week is {@code SaleCommandService.createSale}, which always produces
 * a brand-new {@code SCHEDULED} sale. It does not yet handle updating an existing row across
 * a state transition (e.g. re-persisting after {@code activate()}), because nothing calls
 * that path yet (no Scheduler, no PATCH endpoint — both later weeks). When that path is
 * built, note that {@code flash_sales} keeps a cumulative timestamp per milestone
 * (scheduled_at/activated_at/ended_at/archived_at all coexist on one row per
 * DatabaseSchema.md §2.1) while the aggregate's {@code SaleStatus} only carries the
 * *current* state's timestamp — an update path must fetch-and-mutate the managed entity
 * (not construct a fresh detached one) so JPA's dirty-checking preserves the
 * already-persisted milestone columns instead of nulling them out.
 */
@Repository
public class SaleRepository {

    private final SpringDataFlashSaleRepository flashSaleRepository;
    private final SpringDataSaleScheduleRepository saleScheduleRepository;
    private final SpringDataSaleStatusHistoryRepository saleStatusHistoryRepository;

    public SaleRepository(SpringDataFlashSaleRepository flashSaleRepository,
                           SpringDataSaleScheduleRepository saleScheduleRepository,
                           SpringDataSaleStatusHistoryRepository saleStatusHistoryRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.saleScheduleRepository = saleScheduleRepository;
        this.saleStatusHistoryRepository = saleStatusHistoryRepository;
    }

    /**
     * Persists a newly created {@code FlashSale}. See class javadoc — insert-only in Week 2.
     *
     * @throws UnsupportedOperationException if {@code sale} is not in {@code SCHEDULED}
     *         status. This method always writes {@code scheduled_at} from the aggregate's
     *         current status only; for any other status that column would be persisted as
     *         {@code NULL}, violating the {@code NOT NULL} constraint in schema.sql. Since
     *         the only Week 2 caller ({@code SaleCommandService.createSale}) only ever
     *         produces fresh {@code SCHEDULED} sales, this guard should never trip today —
     *         it exists so that whoever wires the Week 3+ update path hits a clear failure
     *         instead of silently corrupting a row.
     */
    public void save(FlashSale sale) {
        if (!(sale.status() instanceof SaleStatus.Scheduled)) {
            throw new UnsupportedOperationException(
                    "SaleRepository.save() only supports inserting new SCHEDULED sales in Week 2. "
                            + "Sale " + sale.id() + " is " + sale.status().code() + ". "
                            + "Persisting a transition requires fetch-and-mutate of the managed entity "
                            + "so prior milestone timestamps are preserved — see class javadoc.");
        }
        Instant createdAt = milestoneInstant(sale.status());

        FlashSaleJpaEntity saleEntity = new FlashSaleJpaEntity(
                sale.id().value(),
                sale.name(),
                sale.productId().value(),
                sale.totalStock(),
                sale.status().code(),
                statusScheduledAt(sale.status()),
                statusActivatedAt(sale.status()),
                statusEndedAt(sale.status()),
                statusArchivedAt(sale.status()),
                statusEndReason(sale.status()),
                sale.version(),
                createdAt,
                createdAt
        );
        flashSaleRepository.save(saleEntity);

        SaleSchedule schedule = sale.schedule();
        SaleScheduleJpaEntity scheduleEntity = new SaleScheduleJpaEntity(
                schedule.scheduleId(),
                sale.id().value(),
                schedule.window().start(),
                schedule.window().end(),
                schedule.timezone().getId(),
                schedule.version(),
                createdAt,
                createdAt
        );
        saleScheduleRepository.save(scheduleEntity);
    }

    /** Appends one immutable row to {@code sale_status_history} (US-004, FR-008). */
    public void appendStatusHistory(SaleId saleId, String fromStatus, String toStatus,
                                     Instant transitionedAt, String actor, String reason) {
        saleStatusHistoryRepository.save(new SaleStatusHistoryJpaEntity(
                UUID.randomUUID(), saleId.value(), fromStatus, toStatus, transitionedAt, actor, reason
        ));
    }

    public Optional<FlashSale> findById(SaleId saleId) {
        return flashSaleRepository.findById(saleId.value())
                .map(saleEntity -> {
                    SaleScheduleJpaEntity scheduleEntity = saleScheduleRepository.findBySaleId(saleEntity.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Data integrity violation: no sale_schedules row for sale " + saleEntity.getId()));
                    return toAggregate(saleEntity, scheduleEntity);
                });
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    private FlashSale toAggregate(FlashSaleJpaEntity saleEntity, SaleScheduleJpaEntity scheduleEntity) {
        SaleSchedule schedule = SaleSchedule.reconstitute(
                scheduleEntity.getId(),
                new com.flashsale.sale.domain.vo.SaleWindow(scheduleEntity.getSaleStart(), scheduleEntity.getSaleEnd()),
                ZoneId.of(scheduleEntity.getTimezone()),
                scheduleEntity.getVersion()
        );
        SaleStatus status = toDomainStatus(saleEntity);
        return FlashSale.reconstitute(
                SaleId.of(saleEntity.getId()),
                saleEntity.getName(),
                ProductId.of(saleEntity.getProductId()),
                saleEntity.getTotalStock(),
                schedule,
                status,
                saleEntity.getVersion()
        );
    }

    private SaleStatus toDomainStatus(FlashSaleJpaEntity e) {
        return switch (e.getStatus()) {
            case "SCHEDULED" -> new SaleStatus.Scheduled(e.getScheduledAt());
            case "ACTIVE" -> new SaleStatus.Active(e.getActivatedAt());
            case "ENDED" -> new SaleStatus.Ended(e.getEndedAt(), EndReason.valueOf(e.getEndReason()));
            case "ARCHIVED" -> new SaleStatus.Archived(e.getArchivedAt());
            default -> throw new IllegalStateException("Unknown persisted sale status: " + e.getStatus());
        };
    }

    private Instant milestoneInstant(SaleStatus status) {
        return switch (status) {
            case SaleStatus.Scheduled s -> s.scheduledAt();
            case SaleStatus.Active a -> a.activatedAt();
            case SaleStatus.Ended e -> e.endedAt();
            case SaleStatus.Archived ar -> ar.archivedAt();
        };
    }

    private Instant statusScheduledAt(SaleStatus status) {
        return status instanceof SaleStatus.Scheduled s ? s.scheduledAt() : null;
    }

    private Instant statusActivatedAt(SaleStatus status) {
        return status instanceof SaleStatus.Active a ? a.activatedAt() : null;
    }

    private Instant statusEndedAt(SaleStatus status) {
        return status instanceof SaleStatus.Ended e ? e.endedAt() : null;
    }

    private Instant statusArchivedAt(SaleStatus status) {
        return status instanceof SaleStatus.Archived ar ? ar.archivedAt() : null;
    }

    private String statusEndReason(SaleStatus status) {
        return status instanceof SaleStatus.Ended e ? e.reason().name() : null;
    }
}
