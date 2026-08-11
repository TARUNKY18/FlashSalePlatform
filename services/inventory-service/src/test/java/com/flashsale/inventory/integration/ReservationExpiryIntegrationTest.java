package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.flashsale.inventory.application.ReservationExpiryService;
import com.flashsale.inventory.application.port.ReservationRepository;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.aggregate.Reservation.Status;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationExpiryIntegrationTest extends InventoryInfrastructureTestSupport {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SALE_UUID =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID STOCK_LEVEL_UUID =
            UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final int TOTAL_ALLOCATED = 100;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationExpiryService expiryService;

    @BeforeEach
    void insertProductAndStock() {
        insertProductWithStock(
                PRODUCT_UUID, STOCK_LEVEL_UUID, SALE_UUID,
                TOTAL_ALLOCATED, 0L, TOTAL_ALLOCATED, TOTAL_ALLOCATED, 1L
        );
    }

    @Test
    void expiredPendingReservationTransitionedToExpired() {
        Reservation reservation = savePendingAlreadyExpired(UUID.randomUUID(), SALE_UUID, 5);

        expiryService.expireReservations();

        Reservation loaded = reservationRepository.findById(reservation.id()).orElseThrow();
        assertInstanceOf(Status.Expired.class, loaded.status());
    }

    @Test
    void redisStockRestoredAfterExpiry() {
        String stockKey = "stock:{" + SALE_UUID + "}";
        redisTemplate.opsForValue().set(stockKey, "30");

        savePendingAlreadyExpired(UUID.randomUUID(), SALE_UUID, 5);

        expiryService.expireReservations();

        String afterValue = redisTemplate.opsForValue().get(stockKey);
        assertEquals("35", afterValue);
    }

    @Test
    void ceilingEnforcedWhenStockWouldExceedTotalAllocated() {
        String stockKey = "stock:{" + SALE_UUID + "}";
        redisTemplate.opsForValue().set(stockKey, "98");

        savePendingAlreadyExpired(UUID.randomUUID(), SALE_UUID, 10);

        expiryService.expireReservations();

        String afterValue = redisTemplate.opsForValue().get(stockKey);
        assertEquals(Integer.toString(TOTAL_ALLOCATED), afterValue);
    }

    @Test
    void missingRedisKeyDoesNotPreventDbExpiry() {
        Reservation reservation = savePendingAlreadyExpired(UUID.randomUUID(), SALE_UUID, 3);

        expiryService.expireReservations();

        Reservation loaded = reservationRepository.findById(reservation.id()).orElseThrow();
        assertInstanceOf(Status.Expired.class, loaded.status());
    }

    @Test
    void confirmedReservationNotTouchedBySweep() {
        UUID userId = UUID.randomUUID();
        Reservation reservation = saveReservation(
                userId, SALE_UUID, Instant.parse("2099-01-01T00:00:00Z"), 1);

        expiryService.expireReservations();

        Reservation loaded = reservationRepository.findById(reservation.id()).orElseThrow();
        assertInstanceOf(Status.Pending.class, loaded.status());
    }

    // --- helpers ---

    private Reservation savePendingAlreadyExpired(UUID userUuid, UUID saleUuid, int qty) {
        Instant pastExpiry = Instant.now().minusSeconds(60);
        Instant createdAt  = pastExpiry.minusSeconds(600);
        Reservation r = Reservation.create(
                UserId.of(userUuid),
                SaleId.of(saleUuid),
                ProductId.of(PRODUCT_UUID),
                Quantity.of(qty),
                new ReservationExpiry(pastExpiry),
                createdAt,
                UUID.randomUUID().toString()
        );
        return reservationRepository.save(r);
    }

    private Reservation saveReservation(UUID userUuid, UUID saleUuid, Instant expiresAt, int qty) {
        Reservation r = Reservation.create(
                UserId.of(userUuid),
                SaleId.of(saleUuid),
                ProductId.of(PRODUCT_UUID),
                Quantity.of(qty),
                new ReservationExpiry(expiresAt),
                Instant.now().minusSeconds(10),
                UUID.randomUUID().toString()
        );
        return reservationRepository.save(r);
    }
}
