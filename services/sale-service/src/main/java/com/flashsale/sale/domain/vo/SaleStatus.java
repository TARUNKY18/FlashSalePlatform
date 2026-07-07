package com.flashsale.sale.domain.vo;

import java.time.Instant;
import java.util.Objects;

/**
 * The {@code FlashSale} state machine, modelled as a Java 21 sealed interface.
 *
 * {@code SCHEDULED -> ACTIVE -> ENDED -> ARCHIVED}. No state is skippable. No reverse
 * transition is permitted except {@code ACTIVE -> ENDED} via admin force-end (FR-002).
 *
 * Sealed + records over an enum: each state carries the data specific to how it was
 * reached (e.g. {@link Ended} carries {@link EndReason}), and the compiler rejects any
 * {@code switch} over {@code SaleStatus} that omits a case — an illegal state transition
 * that skips handling a state is a compile error, not a runtime surprise.
 */
public sealed interface SaleStatus
        permits SaleStatus.Scheduled, SaleStatus.Active, SaleStatus.Ended, SaleStatus.Archived {

    record Scheduled(Instant scheduledAt) implements SaleStatus {
        public Scheduled {
            Objects.requireNonNull(scheduledAt, "scheduledAt must not be null");
        }
    }

    record Active(Instant activatedAt) implements SaleStatus {
        public Active {
            Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        }
    }

    record Ended(Instant endedAt, EndReason reason) implements SaleStatus {
        public Ended {
            Objects.requireNonNull(endedAt, "endedAt must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    record Archived(Instant archivedAt) implements SaleStatus {
        public Archived {
            Objects.requireNonNull(archivedAt, "archivedAt must not be null");
        }
    }

    /** The status name as persisted in the {@code flash_sales.status} VARCHAR column. */
    default String code() {
        return switch (this) {
            case Scheduled s -> "SCHEDULED";
            case Active a -> "ACTIVE";
            case Ended e -> "ENDED";
            case Archived ar -> "ARCHIVED";
        };
    }
}
