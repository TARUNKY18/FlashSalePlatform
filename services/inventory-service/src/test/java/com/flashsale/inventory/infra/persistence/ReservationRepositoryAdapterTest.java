package com.flashsale.inventory.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.aggregate.Reservation.Status;
import com.flashsale.inventory.domain.vo.OrderId;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.ReservationId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReservationRepositoryAdapterTest {

    private static final UUID RESERVATION_UUID =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final ReservationId RESERVATION_ID = ReservationId.of(RESERVATION_UUID);

    private final SpringDataReservationRepository springDataRepository =
            mock(SpringDataReservationRepository.class);
    private final ReservationPersistenceMapper mapper = mock(ReservationPersistenceMapper.class);
    private final ReservationRepository repository =
            new ReservationRepository(springDataRepository, mapper);

    @Test
    void loadsAndMapsReservation() {
        ReservationJpaEntity entity = stubEntity();
        Reservation aggregate = stubDomain();
        when(springDataRepository.findById(RESERVATION_UUID)).thenReturn(Optional.of(entity));
        when(mapper.toDomain(entity)).thenReturn(aggregate);

        Reservation loaded = repository.findById(RESERVATION_ID).orElseThrow();

        assertSame(aggregate, loaded);
        verify(springDataRepository).findById(RESERVATION_UUID);
        verify(mapper).toDomain(entity);
    }

    @Test
    void returnsEmptyWhenReservationDoesNotExist() {
        when(springDataRepository.findById(RESERVATION_UUID)).thenReturn(Optional.empty());

        Optional<Reservation> result = repository.findById(RESERVATION_ID);

        assertTrue(result.isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void saveDelegatesToSaveAndFlushThenMapsBack() {
        Reservation aggregate = stubDomain();
        ReservationJpaEntity entity   = stubEntity();
        ReservationJpaEntity saved    = stubEntity();
        Reservation reconstituted     = stubDomain();
        when(mapper.toJpaEntity(aggregate)).thenReturn(entity);
        when(springDataRepository.saveAndFlush(entity)).thenReturn(saved);
        when(mapper.toDomain(saved)).thenReturn(reconstituted);

        Reservation result = repository.save(aggregate);

        assertSame(reconstituted, result);
        verify(springDataRepository).saveAndFlush(entity);
        verify(mapper).toDomain(saved);
    }

    @Test
    void saveUpdatesExistingEntityInPlaceWhenFoundById() {
        ReservationJpaEntity existing = stubEntity();
        ReservationJpaEntity saved    = stubEntity();
        Reservation confirmed = Reservation.reconstitute(
                RESERVATION_ID,
                UserId.of(UUID.randomUUID()),
                SaleId.of(UUID.randomUUID()),
                ProductId.of(UUID.randomUUID()),
                Quantity.one(),
                new ReservationExpiry(Instant.parse("2099-01-01T00:00:00Z")),
                new Status.Confirmed(),
                OrderId.of(UUID.randomUUID()),
                1L,
                null
        );
        Reservation reconstituted = stubDomain();
        when(springDataRepository.findById(RESERVATION_UUID)).thenReturn(Optional.of(existing));
        when(springDataRepository.saveAndFlush(existing)).thenReturn(saved);
        when(mapper.toDomain(saved)).thenReturn(reconstituted);

        Reservation result = repository.save(confirmed);

        assertSame(reconstituted, result);
        verify(springDataRepository).saveAndFlush(existing);
        verify(mapper, never()).toJpaEntity(any());
    }

    @Test
    void findByIdRejectsNullId() {
        assertThrows(NullPointerException.class, () -> repository.findById(null));
    }

    @Test
    void saveRejectsNullReservation() {
        assertThrows(NullPointerException.class, () -> repository.save(null));
    }

    // --- helpers ---

    @Test
    void findByIdempotencyKeyDelegatesToSpringData() {
        String iKey = "test-key";
        ReservationJpaEntity entity = stubEntity();
        Reservation aggregate = stubDomain();
        when(springDataRepository.findByIdempotencyKey(iKey)).thenReturn(Optional.of(entity));
        when(mapper.toDomain(entity)).thenReturn(aggregate);

        Reservation loaded = repository.findByIdempotencyKey(iKey).orElseThrow();

        assertSame(aggregate, loaded);
    }

    @Test
    void findByIdempotencyKeyReturnsEmptyWhenNotFound() {
        when(springDataRepository.findByIdempotencyKey("missing")).thenReturn(Optional.empty());

        assertTrue(repository.findByIdempotencyKey("missing").isEmpty());
        verifyNoInteractions(mapper);
    }

    private ReservationJpaEntity stubEntity() {
        return new ReservationJpaEntity(
                RESERVATION_UUID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PENDING",
                1,
                Instant.parse("2099-01-01T00:00:00Z"),
                null,
                null,
                0L
        );
    }

    private Reservation stubDomain() {
        return Reservation.reconstitute(
                RESERVATION_ID,
                UserId.of(UUID.randomUUID()),
                SaleId.of(UUID.randomUUID()),
                ProductId.of(UUID.randomUUID()),
                Quantity.one(),
                new ReservationExpiry(Instant.parse("2099-01-01T00:00:00Z")),
                new Status.Pending(),
                null,
                0L,
                null
        );
    }
}
