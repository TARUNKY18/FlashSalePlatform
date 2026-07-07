package com.flashsale.sale.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flashsale.sale.domain.exception.SaleCreationException;
import com.flashsale.sale.domain.vo.EndReason;
import com.flashsale.sale.domain.vo.ProductId;
import com.flashsale.sale.domain.vo.SaleStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FlashSale state machine coverage — Build-Plan.md Week 2 Definition of Done:
 * "8 unit tests pass covering all state machine paths" (4 valid transitions,
 * 4 illegal transitions throwing {@link IllegalStateException}).
 *
 * <p>Pure domain-layer tests — no Spring context, no database. This is possible only
 * because {@code FlashSale} has zero framework dependencies.
 */
class FlashSaleStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant SALE_START = NOW.plus(Duration.ofHours(1));
    private static final Instant SALE_END = NOW.plus(Duration.ofHours(2));
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.randomUUID());

    private FlashSale newScheduledSale() {
        return FlashSale.schedule(
                "Test Sale", PRODUCT_ID, 100, SALE_START, SALE_END, ZoneId.of("UTC"), NOW
        );
    }

    // ------------------------------------------------------------------
    // Valid transitions (4)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Valid transitions")
    class ValidTransitions {

        @Test
        @DisplayName("V1: SCHEDULED -> ACTIVE via activate()")
        void scheduledToActive() {
            FlashSale sale = newScheduledSale();
            Instant activationTime = SALE_START;

            sale.activate(activationTime);

            assertTrue(sale.status() instanceof SaleStatus.Active);
            assertEquals(activationTime, ((SaleStatus.Active) sale.status()).activatedAt());
            assertEquals(1L, sale.version());
        }

        @Test
        @DisplayName("V2: ACTIVE -> ENDED via end() — natural TIME_ELAPSED")
        void activeToEndedTimeElapsed() {
            FlashSale sale = newScheduledSale();
            sale.activate(SALE_START);

            sale.end(SALE_END, EndReason.TIME_ELAPSED);

            assertTrue(sale.status() instanceof SaleStatus.Ended);
            SaleStatus.Ended ended = (SaleStatus.Ended) sale.status();
            assertEquals(SALE_END, ended.endedAt());
            assertEquals(EndReason.TIME_ELAPSED, ended.reason());
            assertEquals(2L, sale.version());
        }

        @Test
        @DisplayName("V3: ACTIVE -> ENDED via end() — admin force-end (US-003)")
        void activeToEndedAdminForce() {
            FlashSale sale = newScheduledSale();
            sale.activate(SALE_START);
            Instant forceEndTime = SALE_START.plus(Duration.ofMinutes(10));

            sale.end(forceEndTime, EndReason.ADMIN_FORCE);

            assertTrue(sale.status() instanceof SaleStatus.Ended);
            assertEquals(EndReason.ADMIN_FORCE, ((SaleStatus.Ended) sale.status()).reason());
        }

        @Test
        @DisplayName("V4: ENDED -> ARCHIVED via archive()")
        void endedToArchived() {
            FlashSale sale = newScheduledSale();
            sale.activate(SALE_START);
            sale.end(SALE_END, EndReason.TIME_ELAPSED);
            Instant archiveTime = SALE_END.plus(Duration.ofMinutes(5));

            sale.archive(archiveTime);

            assertTrue(sale.status() instanceof SaleStatus.Archived);
            assertEquals(archiveTime, ((SaleStatus.Archived) sale.status()).archivedAt());
            assertEquals(3L, sale.version());
        }
    }

    // ------------------------------------------------------------------
    // Illegal transitions (4)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Illegal transitions")
    class IllegalTransitions {

        @Test
        @DisplayName("I1: ACTIVE -> ACTIVE via activate() throws IllegalStateException")
        void cannotActivateAlreadyActiveSale() {
            FlashSale sale = newScheduledSale();
            sale.activate(SALE_START);

            assertThrows(IllegalStateException.class, () -> sale.activate(SALE_START.plusSeconds(1)));
        }

        @Test
        @DisplayName("I2: ENDED -> ACTIVE via activate() throws IllegalStateException")
        void cannotActivateEndedSale() {
            FlashSale sale = newScheduledSale();
            sale.activate(SALE_START);
            sale.end(SALE_END, EndReason.TIME_ELAPSED);

            assertThrows(IllegalStateException.class, () -> sale.activate(SALE_END.plusSeconds(1)));
        }

        @Test
        @DisplayName("I3: SCHEDULED -> ENDED via end() throws IllegalStateException (skips ACTIVE)")
        void cannotEndScheduledSale() {
            FlashSale sale = newScheduledSale();

            assertThrows(IllegalStateException.class,
                    () -> sale.end(SALE_START, EndReason.ADMIN_FORCE));
        }

        @Test
        @DisplayName("I4: ACTIVE -> ARCHIVED via archive() throws IllegalStateException (skips ENDED)")
        void cannotArchiveActiveSale() {
            FlashSale sale = newScheduledSale();
            sale.activate(SALE_START);

            assertThrows(IllegalStateException.class, () -> sale.archive(SALE_START.plusSeconds(1)));
        }
    }

    // ------------------------------------------------------------------
    // Supplementary: creation-time validation (EC-002 / EC-003 / EC-004)
    // Not counted against the required 8 — these cover FlashSale.schedule(...)
    // itself rather than the post-creation state machine.
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Creation validation (supplementary)")
    class CreationValidation {

        @Test
        @DisplayName("EC-003: totalStock <= 0 throws SaleCreationException.INVALID_STOCK")
        void rejectsNonPositiveStock() {
            SaleCreationException ex = assertThrows(SaleCreationException.class, () ->
                    FlashSale.schedule("Test", PRODUCT_ID, 0, SALE_START, SALE_END, ZoneId.of("UTC"), NOW));

            assertEquals(SaleCreationException.ErrorCode.INVALID_STOCK, ex.errorCode());
        }

        @Test
        @DisplayName("EC-002: saleStart not in the future throws SaleCreationException.INVALID_SALE_START")
        void rejectsPastSaleStart() {
            Instant pastStart = NOW.minus(Duration.ofMinutes(1));

            SaleCreationException ex = assertThrows(SaleCreationException.class, () ->
                    FlashSale.schedule("Test", PRODUCT_ID, 100, pastStart, SALE_END, ZoneId.of("UTC"), NOW));

            assertEquals(SaleCreationException.ErrorCode.INVALID_SALE_START, ex.errorCode());
        }

        @Test
        @DisplayName("EC-004: saleEnd before saleStart throws SaleCreationException.INVALID_SALE_WINDOW")
        void rejectsSaleEndBeforeSaleStart() {
            Instant invalidEnd = SALE_START.minus(Duration.ofMinutes(1));

            SaleCreationException ex = assertThrows(SaleCreationException.class, () ->
                    FlashSale.schedule("Test", PRODUCT_ID, 100, SALE_START, invalidEnd, ZoneId.of("UTC"), NOW));

            assertEquals(SaleCreationException.ErrorCode.INVALID_SALE_WINDOW, ex.errorCode());
        }

        @Test
        @DisplayName("US-001: a freshly scheduled sale is SCHEDULED and raises SaleScheduled")
        void schedulingRaisesSaleScheduledEvent() {
            FlashSale sale = newScheduledSale();

            assertTrue(sale.status() instanceof SaleStatus.Scheduled);
            assertEquals(1, sale.pullDomainEvents().size());
            assertTrue(sale.pullDomainEvents().isEmpty(), "events must be cleared after pulling");
        }
    }
}
