package com.flashsale.sale.domain.entity;

import com.flashsale.sale.domain.vo.SaleWindow;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code SaleSchedule} entity, owned by the {@code FlashSale} aggregate
 * (DomainModel.md §3.1).
 *
 * <p>Holds the {@link SaleWindow} value object plus timezone. Never referenced directly
 * from outside the {@code FlashSale} aggregate boundary — only via the aggregate root.
 *
 * <p>Immutability contract (DatabaseSchema.md §2.2): becomes logically immutable once the
 * parent sale transitions to {@code ACTIVE}. This is <b>not</b> enforced by this entity
 * itself — the database does not enforce it either. It is enforced by the {@code FlashSale}
 * aggregate root, which is the only thing permitted to mutate this entity.
 */
public final class SaleSchedule {

    private final UUID scheduleId;
    private final SaleWindow window;
    private final ZoneId timezone;
    private final long version;

    private SaleSchedule(UUID scheduleId, SaleWindow window, ZoneId timezone, long version) {
        this.scheduleId = Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        this.window = Objects.requireNonNull(window, "window must not be null");
        this.timezone = Objects.requireNonNull(timezone, "timezone must not be null");
        this.version = version;
    }

    /** Creates a new schedule for a sale being created for the first time. */
    public static SaleSchedule create(SaleWindow window, ZoneId timezone) {
        return new SaleSchedule(UUID.randomUUID(), window, timezone, 0L);
    }

    /** Reconstitutes a schedule from persisted state (used by the infra-layer mapper). */
    public static SaleSchedule reconstitute(UUID scheduleId, SaleWindow window, ZoneId timezone, long version) {
        return new SaleSchedule(scheduleId, window, timezone, version);
    }

    public UUID scheduleId() {
        return scheduleId;
    }

    public SaleWindow window() {
        return window;
    }

    public ZoneId timezone() {
        return timezone;
    }

    public long version() {
        return version;
    }
}
