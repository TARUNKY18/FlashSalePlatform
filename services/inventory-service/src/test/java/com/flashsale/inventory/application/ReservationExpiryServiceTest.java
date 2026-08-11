package com.flashsale.inventory.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.ReservationRepository;
import com.flashsale.inventory.application.port.StockReleasePort;
import com.flashsale.inventory.application.port.StockReleaseUnavailableException;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ReservationExpiryServiceTest {

    private ReservationRepository reservationRepository;
    private ProductRepository productRepository;
    private StockReleasePort stockReleasePort;
    private Clock clock;
    private ReservationExpiryService service;

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    @BeforeEach
    void setUp() {
        reservationRepository = mock(ReservationRepository.class);
        productRepository     = mock(ProductRepository.class);
        stockReleasePort      = mock(StockReleasePort.class);
        clock                 = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ReservationExpiryService(
                reservationRepository, productRepository, stockReleasePort, clock);
    }

    @Test
    void noExpiredReservationsDoesNothing() {
        when(reservationRepository.findExpiredPending(NOW)).thenReturn(List.of());

        service.expireReservations();

        verify(reservationRepository, never()).save(any());
        verify(stockReleasePort, never()).release(any(), any(int.class), any(int.class));
    }

    @Test
    void expiredReservationIsTransitionedAndStockReleased() {
        Reservation reservation = pendingReservation(UUID.randomUUID(), UUID.randomUUID(), 3);
        SaleId saleId = reservation.saleId();

        Product product = productWithAllocation(reservation.productId(), saleId, 100, 100);
        when(reservationRepository.findExpiredPending(NOW)).thenReturn(List.of(reservation));
        when(productRepository.findById(any())).thenReturn(Optional.of(product));
        when(stockReleasePort.release(any(), anyInt(), anyInt()))
                .thenReturn(StockReleaseResult.RELEASED);

        service.expireReservations();

        verify(reservationRepository).save(reservation);
        verify(stockReleasePort).release(saleId, 3, 100);
    }

    @Test
    void saveCalledBeforeStockRelease() {
        Reservation reservation = pendingReservation(UUID.randomUUID(), UUID.randomUUID(), 1);
        Product product = productWithAllocation(
                reservation.productId(), reservation.saleId(), 50, 1);

        when(reservationRepository.findExpiredPending(NOW)).thenReturn(List.of(reservation));
        when(productRepository.findById(any())).thenReturn(Optional.of(product));
        when(stockReleasePort.release(any(), anyInt(), anyInt()))
                .thenReturn(StockReleaseResult.RELEASED);

        service.expireReservations();

        InOrder order = inOrder(reservationRepository, stockReleasePort);
        order.verify(reservationRepository).save(any());
        order.verify(stockReleasePort).release(any(), anyInt(), anyInt());
    }

    @Test
    void saleEndedResultIsToleratedSweepContinues() {
        Reservation reservation = pendingReservation(UUID.randomUUID(), UUID.randomUUID(), 2);
        Product product = productWithAllocation(
                reservation.productId(), reservation.saleId(), 50, 2);

        when(reservationRepository.findExpiredPending(NOW)).thenReturn(List.of(reservation));
        when(productRepository.findById(any())).thenReturn(Optional.of(product));
        when(stockReleasePort.release(any(), anyInt(), anyInt()))
                .thenReturn(StockReleaseResult.SALE_ENDED);

        service.expireReservations();

        verify(reservationRepository).save(reservation);
    }

    @Test
    void stockReleaseUnavailableIsToleratedReservationStillExpired() {
        Reservation reservation = pendingReservation(UUID.randomUUID(), UUID.randomUUID(), 1);
        Product product = productWithAllocation(
                reservation.productId(), reservation.saleId(), 50, 1);

        when(reservationRepository.findExpiredPending(NOW)).thenReturn(List.of(reservation));
        when(productRepository.findById(any())).thenReturn(Optional.of(product));
        when(stockReleasePort.release(any(), anyInt(), anyInt()))
                .thenThrow(new StockReleaseUnavailableException("Redis down"));

        service.expireReservations();

        verify(reservationRepository).save(reservation);
    }

    @Test
    void missingProductToleratedReservationStillExpired() {
        Reservation reservation = pendingReservation(UUID.randomUUID(), UUID.randomUUID(), 1);

        when(reservationRepository.findExpiredPending(NOW)).thenReturn(List.of(reservation));
        when(productRepository.findById(reservation.productId())).thenReturn(Optional.empty());

        service.expireReservations();

        verify(reservationRepository).save(reservation);
        verify(stockReleasePort, never()).release(any(), any(int.class), any(int.class));
    }

    @Test
    void multipleExpiredReservationsAllProcessed() {
        Reservation r1 = pendingReservation(UUID.randomUUID(), UUID.randomUUID(), 1);
        Reservation r2 = pendingReservation(UUID.randomUUID(), UUID.randomUUID(), 2);

        when(reservationRepository.findExpiredPending(NOW)).thenReturn(List.of(r1, r2));
        when(productRepository.findById(any())).thenReturn(
                Optional.of(productWithAllocation(r1.productId(), r1.saleId(), 50, 1)));
        when(stockReleasePort.release(any(), anyInt(), anyInt()))
                .thenReturn(StockReleaseResult.RELEASED);

        service.expireReservations();

        verify(reservationRepository).save(r1);
        verify(reservationRepository).save(r2);
    }

    // --- helpers ---

    private Reservation pendingReservation(UUID userUuid, UUID saleUuid, int qty) {
        UUID productUuid = UUID.randomUUID();
        return Reservation.create(
                UserId.of(userUuid),
                SaleId.of(saleUuid),
                ProductId.of(productUuid),
                Quantity.of(qty),
                new ReservationExpiry(NOW.minusSeconds(1)),
                NOW.minusSeconds(600),
                UUID.randomUUID().toString()
        );
    }

    private Product productWithAllocation(
            ProductId productId, SaleId saleId, int totalStock, int allocated
    ) {
        Product product = Product.create(productId, StockCount.of(totalStock));
        product.allocateStock(saleId, StockCount.of(allocated));
        return product;
    }
}
