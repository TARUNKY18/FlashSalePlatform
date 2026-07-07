package com.flashsale.sale.domain.aggregate;

import com.flashsale.sale.domain.entity.SaleSchedule;
import com.flashsale.sale.domain.event.SaleArchived;
import com.flashsale.sale.domain.event.SaleEnded;
import com.flashsale.sale.domain.event.SaleScheduled;
import com.flashsale.sale.domain.event.SaleStarted;
import com.flashsale.sale.domain.exception.SaleCreationException;
import com.flashsale.sale.domain.vo.EndReason;
import com.flashsale.sale.domain.vo.ProductId;
import com.flashsale.sale.domain.vo.SaleId;
import com.flashsale.sale.domain.vo.SaleStatus;
import com.flashsale.sale.domain.vo.SaleWindow;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@code FlashSale} aggregate root (DomainModel.md §2.1).
 *
 * <p><b>Core invariant:</b> the sale status machine is the only valid transition path.
 * No state may be skipped. No reverse transition is permitted except
 * {@code ACTIVE -> ENDED} via admin (FR-002).
 *
 * <pre>
 * SCHEDULED --(activate)--&gt; ACTIVE --(end)--&gt; ENDED --(archive)--&gt; ARCHIVED
 * </pre>
 *
 * <p>This class has zero Spring/JPA dependencies (README.md repository structure:
 * "domain/ — Aggregates, VOs, events — zero Spring dependencies"). Persistence mapping
 * lives entirely in the {@code infra} package.
 *
 * <p><b>Week 2 scope:</b> only the commands needed to satisfy the Week 2 Definition of
 * Done are implemented: {@link #schedule}, {@link #activate}, {@link #end}, {@link #archive}.
 * Domain events are raised and held internally ({@link #pullDomainEvents()}) but nothing
 * publishes them yet — Kafka wiring is Week 6 (Build-Plan.md).
 */
public final class FlashSale {

    private final SaleId id;
    private final String name;
    private final ProductId productId;
    private final int totalStock;
    private final SaleSchedule schedule;

    private SaleStatus status;
    private long version;

    private final List<Object> domainEvents = new ArrayList<>();

    private FlashSale(SaleId id, String name, ProductId productId, int totalStock,
                       SaleSchedule schedule, SaleStatus status, long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.totalStock = totalStock;
        this.schedule = Objects.requireNonNull(schedule, "schedule must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.version = version;
    }

    // ------------------------------------------------------------------
    // Command: ScheduleSale — creation
    // ------------------------------------------------------------------

    /**
     * Creates a new {@code FlashSale} in {@code SCHEDULED} status.
     *
     * <p>Takes raw {@code saleStart}/{@code saleEnd} instants (rather than a pre-built
     * {@link SaleWindow}) so that <b>every</b> creation-time validation failure — including
     * a malformed window — surfaces through {@link SaleCreationException} with the specific
     * PRD error code, instead of a generic {@link IllegalArgumentException} escaping from
     * {@link SaleWindow}'s own compact constructor before this method gets a chance to
     * translate it.
     *
     * <p>Enforces US-001's acceptance criteria and the corresponding edge cases:
     * <ul>
     *   <li>EC-004 — {@code saleEnd} must be after {@code saleStart}</li>
     *   <li>EC-003 — {@code totalStock} must be &gt; 0</li>
     *   <li>EC-002 — {@code saleStart} must be in the future relative to {@code now}</li>
     * </ul>
     *
     * @param now the reference instant used to validate {@code saleStart} is in the future.
     *            Passed explicitly rather than read via {@code Instant.now()} so the
     *            aggregate stays deterministic and unit-testable.
     */
    public static FlashSale schedule(String name, ProductId productId, int totalStock,
                                      Instant saleStart, Instant saleEnd, ZoneId timezone, Instant now) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(saleStart, "saleStart must not be null");
        Objects.requireNonNull(saleEnd, "saleEnd must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        Objects.requireNonNull(now, "now must not be null");

        SaleWindow saleWindow;
        try {
            saleWindow = new SaleWindow(saleStart, saleEnd);
        } catch (IllegalArgumentException e) {
            throw SaleCreationException.invalidSaleWindow();
        }
        if (totalStock <= 0) {
            throw SaleCreationException.invalidStock();
        }
        if (!saleWindow.isUpcoming(now)) {
            throw SaleCreationException.invalidSaleStart();
        }

        SaleId saleId = SaleId.generate();
        SaleSchedule saleSchedule = SaleSchedule.create(saleWindow, timezone);

        FlashSale sale = new FlashSale(
                saleId, name, productId, totalStock, saleSchedule,
                new SaleStatus.Scheduled(now), 0L
        );
        sale.raise(new SaleScheduled(saleId, productId, name, totalStock, saleWindow, now));
        return sale;
    }

    /**
     * Reconstitutes a {@code FlashSale} from persisted state.
     *
     * <p>Used exclusively by the infra-layer repository mapper — this is the only path
     * that may set an arbitrary status/version pair rather than deriving it from a command.
     */
    public static FlashSale reconstitute(SaleId id, String name, ProductId productId, int totalStock,
                                          SaleSchedule schedule, SaleStatus status, long version) {
        return new FlashSale(id, name, productId, totalStock, schedule, status, version);
    }

    // ------------------------------------------------------------------
    // Commands: state machine
    // ------------------------------------------------------------------

    /** {@code SCHEDULED -> ACTIVE}. Triggered by the Scheduler at {@code saleStart} (FR-003). */
    public void activate(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        switch (status) {
            case SaleStatus.Scheduled s -> {
                this.status = new SaleStatus.Active(now);
                this.version++;
                raise(new SaleStarted(id, now));
            }
            case SaleStatus.Active a ->
                    throw new IllegalStateException("Sale " + id + " is already ACTIVE");
            case SaleStatus.Ended e ->
                    throw new IllegalStateException("Cannot activate sale " + id + " — it has ENDED");
            case SaleStatus.Archived ar ->
                    throw new IllegalStateException("Cannot activate sale " + id + " — it is ARCHIVED");
        }
    }

    /**
     * {@code ACTIVE -> ENDED}. Triggered by the Scheduler ({@code saleEnd} reached or stock
     * depleted) or by an admin force-end (FR-004, US-003). This is the one permitted
     * "reverse-looking" edge in the sense that it is the only transition not driven purely
     * by forward progress — it is still forward in the state machine, just admin-triggerable.
     */
    public void end(Instant now, EndReason reason) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        switch (status) {
            case SaleStatus.Active a -> {
                this.status = new SaleStatus.Ended(now, reason);
                this.version++;
                raise(new SaleEnded(id, reason, now));
            }
            case SaleStatus.Scheduled s ->
                    throw new IllegalStateException("Cannot end sale " + id + " — it has not started (still SCHEDULED)");
            case SaleStatus.Ended e ->
                    throw new IllegalStateException("Sale " + id + " has already ENDED");
            case SaleStatus.Archived ar ->
                    throw new IllegalStateException("Cannot end sale " + id + " — it is ARCHIVED");
        }
    }

    /** {@code ENDED -> ARCHIVED}. Triggered asynchronously post-end. */
    public void archive(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        switch (status) {
            case SaleStatus.Ended e -> {
                this.status = new SaleStatus.Archived(now);
                this.version++;
                raise(new SaleArchived(id, now));
            }
            case SaleStatus.Scheduled s ->
                    throw new IllegalStateException("Cannot archive sale " + id + " — it is still SCHEDULED");
            case SaleStatus.Active a ->
                    throw new IllegalStateException("Cannot archive sale " + id + " — it is still ACTIVE");
            case SaleStatus.Archived ar ->
                    throw new IllegalStateException("Sale " + id + " is already ARCHIVED");
        }
    }

    // ------------------------------------------------------------------
    // Domain events
    // ------------------------------------------------------------------

    private void raise(Object event) {
        domainEvents.add(event);
    }

    /** Returns and clears the events raised since the last pull. Not yet consumed in Week 2. */
    public List<Object> pullDomainEvents() {
        List<Object> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public SaleId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public ProductId productId() {
        return productId;
    }

    public int totalStock() {
        return totalStock;
    }

    public SaleSchedule schedule() {
        return schedule;
    }

    public SaleStatus status() {
        return status;
    }

    public long version() {
        return version;
    }
}
