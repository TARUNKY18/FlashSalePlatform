package com.flashsale.inventory.application;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flashsale.inventory.application.port.ReservationDuplicateGuardPort;
import com.flashsale.inventory.application.port.ReservationDuplicateGuardUnavailableException;
import com.flashsale.inventory.application.port.ReservationRepository;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.ReservationId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class ReservationCommandServiceTest {

    private static final Instant NOW       = Instant.parse("2099-01-01T12:00:00Z");
    private static final Clock   FIXED_CLK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UserId    USER_ID    = UserId.of(UUID.randomUUID());
    private static final SaleId    SALE_ID    = SaleId.of(UUID.randomUUID());
    private static final ProductId PRODUCT_ID = ProductId.of(UUID.randomUUID());
    private static final Quantity  QTY        = Quantity.one();
    private static final String    IKEY       = UUID.randomUUID().toString();

    private final ReservationRepository        reservationRepository = mock(ReservationRepository.class);
    private final ReservationDuplicateGuardPort duplicateGuard       = mock(ReservationDuplicateGuardPort.class);
    private final StockCounterService          stockCounterService   = mock(StockCounterService.class);
    private final ReservationCommandService    service               =
            new ReservationCommandService(reservationRepository, duplicateGuard, stockCounterService, FIXED_CLK);

    private final CreateReservationCommand command =
            new CreateReservationCommand(IKEY, USER_ID, SALE_ID, PRODUCT_ID, QTY);

    @BeforeEach
    void defaultStubs() {
        when(reservationRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(duplicateGuard.tryAcquire(any(), any())).thenReturn(true);
        when(stockCounterService.decrement(any(), any(), anyInt()))
                .thenReturn(new StockDecrementResult.Decremented(
                        com.flashsale.inventory.domain.vo.StockCount.of(9)));
        when(reservationRepository.saveWithOutboxEvent(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void happyPathCreatesAndPersistsReservation() {
        ReservationCreatedResult result = service.reserve(command);

        assertInstanceOf(ReservationCreatedResult.Created.class, result);
        Reservation r = ((ReservationCreatedResult.Created) result).reservation();
        assertNotNull(r.id());
        assertInstanceOf(Reservation.Status.Pending.class, r.status());
        ArgumentCaptor<InventoryEvent> eventCaptor = ArgumentCaptor.forClass(InventoryEvent.class);
        verify(reservationRepository).saveWithOutboxEvent(any(), eventCaptor.capture());
        InventoryEvent.StockReserved event =
                assertInstanceOf(InventoryEvent.StockReserved.class, eventCaptor.getValue());
        assertEquals(r.id(), event.reservationId());
        assertEquals(r.id(), event.aggregateId());
        assertEquals(NOW, event.occurredAt());
        assertEquals(9, event.remainingStock());
        assertEquals("1.0", event.eventVersion());
        assertEquals("Reservation", event.aggregateType());
    }

    @Test
    void idempotentReplayReturnsExistingReservationWithoutSaving() {
        Reservation existing = stubReservation();
        when(reservationRepository.findByIdempotencyKey(IKEY)).thenReturn(Optional.of(existing));

        ReservationCreatedResult result = service.reserve(command);

        assertInstanceOf(ReservationCreatedResult.IdempotentReplay.class, result);
        assertSame(existing, ((ReservationCreatedResult.IdempotentReplay) result).reservation());
        verify(reservationRepository, never()).saveWithOutboxEvent(any(), any());
        verify(duplicateGuard, never()).tryAcquire(any(), any());
    }

    @Test
    void soldOutReturnsSoldOutResult() {
        when(stockCounterService.decrement(any(), any(), anyInt()))
                .thenReturn(new StockDecrementResult.SoldOut());

        ReservationCreatedResult result = service.reserve(command);

        assertInstanceOf(ReservationCreatedResult.SoldOut.class, result);
        verify(reservationRepository, never()).saveWithOutboxEvent(any(), any());
    }

    @Test
    void duplicateReservationReturnsDuplicateResult() {
        when(duplicateGuard.tryAcquire(USER_ID, SALE_ID)).thenReturn(false);

        ReservationCreatedResult result = service.reserve(command);

        assertInstanceOf(ReservationCreatedResult.DuplicateReservation.class, result);
        verify(reservationRepository, never()).saveWithOutboxEvent(any(), any());
        verify(stockCounterService, never()).decrement(any(), any(), any(int.class));
    }

    @Test
    void redisUnavailableFallsThroughToDbGuardAndSucceeds() {
        when(duplicateGuard.tryAcquire(USER_ID, SALE_ID))
                .thenThrow(new ReservationDuplicateGuardUnavailableException("Redis down"));

        ListAppender<ILoggingEvent> appender = captureLogFor(ReservationCommandService.class);

        ReservationCreatedResult result = service.reserve(command);

        assertInstanceOf(ReservationCreatedResult.Created.class, result);
        boolean warnLogged = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getMessage().contains("continuing to database guard"));
        org.junit.jupiter.api.Assertions.assertTrue(warnLogged,
                "Expected WARN log about continuing to DB guard");
    }

    private static Reservation stubReservation() {
        return Reservation.reconstitute(
                ReservationId.generate(),
                USER_ID, SALE_ID, PRODUCT_ID, QTY,
                new ReservationExpiry(NOW.plusSeconds(600)),
                new Reservation.Status.Pending(),
                null,
                0L,
                IKEY
        );
    }

    private static ListAppender<ILoggingEvent> captureLogFor(Class<?> clazz) {
        Logger logger = (Logger) LoggerFactory.getLogger(clazz);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
