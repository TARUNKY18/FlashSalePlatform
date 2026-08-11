package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flashsale.inventory.application.port.ReservationRepository;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class ReservationPersistenceIntegrationTest extends InventoryInfrastructureTestSupport {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_UUID);
    private static final Instant EXPIRES_AT = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired
    private ReservationRepository reservationRepository;

    @BeforeEach
    void insertProduct() {
        jdbcTemplate.update(
                "INSERT INTO products (id, total_stock, version) VALUES (?, ?, ?)",
                PRODUCT_UUID, 100, 0L
        );
    }

    @Test
    void flywayAppliesV2Migration() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success",
                Integer.class
        );
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('reservations', 'stock_reservation_log')
                """,
                Integer.class
        );

        assertTrue(migrationCount != null && migrationCount >= 2);
        assertEquals(2, tableCount);
    }

    @Test
    void saveThenFindByIdReconstitutesFullAggregate() {
        Reservation created = newPendingReservation(UUID.randomUUID(), UUID.randomUUID());

        Reservation saved  = reservationRepository.save(created);
        Reservation loaded = reservationRepository.findById(saved.id()).orElseThrow();

        assertEquals(saved.id(),        loaded.id());
        assertEquals(saved.userId(),    loaded.userId());
        assertEquals(saved.saleId(),    loaded.saleId());
        assertEquals(saved.productId(), loaded.productId());
        assertEquals(saved.quantity(),  loaded.quantity());
        assertEquals(saved.expiry(),    loaded.expiry());
        assertInstanceOf(Status.Pending.class, loaded.status());
        assertEquals(saved.version(),   loaded.version());
    }

    @Test
    void saveConfirmedStatusRoundTrips() {
        Reservation pending = newPendingReservation(UUID.randomUUID(), UUID.randomUUID());
        Reservation saved   = reservationRepository.save(pending);

        saved.confirm(OrderId.of(UUID.randomUUID()));
        Reservation confirmed = reservationRepository.save(saved);
        Reservation loaded    = reservationRepository.findById(confirmed.id()).orElseThrow();

        assertInstanceOf(Status.Confirmed.class, loaded.status());
        assertNotNull(loaded.orderId());
    }

    @Test
    void saveExpiredStatusRoundTrips() {
        Reservation pending = newPendingReservation(UUID.randomUUID(), UUID.randomUUID());
        Reservation saved   = reservationRepository.save(pending);

        saved.expire();
        Reservation expired = reservationRepository.save(saved);
        Reservation loaded  = reservationRepository.findById(expired.id()).orElseThrow();

        assertInstanceOf(Status.Expired.class, loaded.status());
    }

    @Test
    void saveReleasedStatusRoundTrips() {
        Reservation pending = newPendingReservation(UUID.randomUUID(), UUID.randomUUID());
        Reservation saved   = reservationRepository.save(pending);

        saved.release("USER_CANCEL");
        Reservation released = reservationRepository.save(saved);
        Reservation loaded   = reservationRepository.findById(released.id()).orElseThrow();

        assertInstanceOf(Status.Released.class, loaded.status());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        assertTrue(reservationRepository.findById(ReservationId.generate()).isEmpty());
    }

    @Test
    void partialUniqueIndexBlocksSecondPendingForSameUserAndSale() {
        UUID userId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        reservationRepository.save(newPendingReservation(userId, saleId));

        Reservation duplicate = newPendingReservation(userId, saleId);

        assertThrows(DataIntegrityViolationException.class,
                () -> reservationRepository.save(duplicate));
    }

    @Test
    void expiredReservationDoesNotBlockNewPendingForSameUserAndSale() {
        UUID userId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        Reservation first = reservationRepository.save(newPendingReservation(userId, saleId));
        first.expire();
        reservationRepository.save(first);

        Reservation second = newPendingReservation(userId, saleId);
        Reservation saved  = reservationRepository.save(second);

        assertInstanceOf(Status.Pending.class,
                reservationRepository.findById(saved.id()).orElseThrow().status());
    }

    @Test
    void findByIdempotencyKeyReturnsReservationWhenExists() {
        String iKey = UUID.randomUUID().toString();
        Reservation created = newPendingReservation(UUID.randomUUID(), UUID.randomUUID(), iKey);
        reservationRepository.save(created);

        Reservation loaded = reservationRepository.findByIdempotencyKey(iKey).orElseThrow();

        assertEquals(iKey, loaded.idempotencyKey());
    }

    @Test
    void findByIdempotencyKeyReturnsEmptyWhenNotFound() {
        assertTrue(reservationRepository.findByIdempotencyKey("no-such-key").isEmpty());
    }

    // --- helpers ---

    private Reservation newPendingReservation(UUID userId, UUID saleId) {
        return newPendingReservation(userId, saleId, UUID.randomUUID().toString());
    }

    private Reservation newPendingReservation(UUID userId, UUID saleId, String idempotencyKey) {
        return Reservation.create(
                UserId.of(userId),
                SaleId.of(saleId),
                PRODUCT_ID,
                Quantity.one(),
                new ReservationExpiry(EXPIRES_AT),
                Instant.now(),
                idempotencyKey
        );
    }
}
