package com.flashsale.inventory.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReservationPersistenceMapperTest {

    private static final UUID RESERVATION_UUID =
            UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID USER_UUID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SALE_UUID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PRODUCT_UUID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ORDER_UUID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant EXPIRES_AT = Instant.parse("2099-01-01T00:00:00Z");

    private final ReservationPersistenceMapper mapper = new ReservationPersistenceMapper();

    @Test
    void mapsDomainToJpaEntity() {
        Reservation domain = pendingReservation();

        ReservationJpaEntity entity = mapper.toJpaEntity(domain);

        assertEquals(RESERVATION_UUID, entity.getId());
        assertEquals(USER_UUID,        entity.getUserId());
        assertEquals(SALE_UUID,        entity.getSaleId());
        assertEquals(PRODUCT_UUID,     entity.getProductId());
        assertEquals("PENDING",        entity.getStatus());
        assertEquals(2,                entity.getQuantity());
        assertEquals(EXPIRES_AT,       entity.getExpiresAt());
        assertNull(entity.getOrderId());
        assertEquals(0L, entity.getVersion());
    }

    @Test
    void mapsNullOrderIdToNull() {
        ReservationJpaEntity entity = mapper.toJpaEntity(pendingReservation());
        assertNull(entity.getOrderId());
    }

    @Test
    void mapsOrderIdWhenPresent() {
        Reservation confirmed = confirmedReservation();
        ReservationJpaEntity entity = mapper.toJpaEntity(confirmed);
        assertEquals(ORDER_UUID, entity.getOrderId());
        assertEquals("CONFIRMED", entity.getStatus());
    }

    @ParameterizedTest
    @CsvSource({"PENDING", "CONFIRMED", "EXPIRED", "RELEASED"})
    void serializesAllStatusVariantsToUppercase(String expectedStatus) {
        Status status = switch (expectedStatus) {
            case "PENDING"   -> new Status.Pending();
            case "CONFIRMED" -> new Status.Confirmed();
            case "EXPIRED"   -> new Status.Expired();
            case "RELEASED"  -> new Status.Released();
            default          -> throw new IllegalArgumentException(expectedStatus);
        };
        Reservation reservation = Reservation.reconstitute(
                ReservationId.of(RESERVATION_UUID),
                UserId.of(USER_UUID),
                SaleId.of(SALE_UUID),
                ProductId.of(PRODUCT_UUID),
                Quantity.of(1),
                new ReservationExpiry(EXPIRES_AT),
                status,
                null,
                0L
        );

        String serialized = mapper.toJpaEntity(reservation).getStatus();

        assertEquals(expectedStatus, serialized);
    }

    @Test
    void mapsJpaEntityToDomain() {
        ReservationJpaEntity entity = jpaEntity("PENDING", null, 0L);

        Reservation domain = mapper.toDomain(entity);

        assertEquals(RESERVATION_UUID, domain.id().value());
        assertEquals(USER_UUID,        domain.userId().value());
        assertEquals(SALE_UUID,        domain.saleId().value());
        assertEquals(PRODUCT_UUID,     domain.productId().value());
        assertEquals(2,                domain.quantity().value());
        assertEquals(EXPIRES_AT,       domain.expiry().expiresAt());
        assertNull(domain.orderId());
        assertEquals(0L, domain.version());
    }

    @Test
    void preservesVersionOnRoundTrip() {
        ReservationJpaEntity entity = jpaEntity("EXPIRED", null, 7L);
        assertEquals(7L, mapper.toDomain(entity).version());
    }

    @Test
    void reconstitutesOrderIdFromJpaEntity() {
        ReservationJpaEntity entity = jpaEntity("CONFIRMED", ORDER_UUID, 1L);
        assertEquals(ORDER_UUID, mapper.toDomain(entity).orderId().value());
    }

    @ParameterizedTest
    @CsvSource({"PENDING", "CONFIRMED", "EXPIRED", "RELEASED"})
    void deserializesAllStatusVariants(String statusString) {
        ReservationJpaEntity entity = jpaEntity(statusString, null, 0L);
        Reservation domain = mapper.toDomain(entity);
        assertEquals(statusString, domain.status().getClass().getSimpleName().toUpperCase());
    }

    @Test
    void rejectsUnknownStatusString() {
        ReservationJpaEntity entity = jpaEntity("UNKNOWN", null, 0L);
        assertThrows(IllegalStateException.class, () -> mapper.toDomain(entity));
    }

    @Test
    void rejectsNullReservation() {
        assertThrows(NullPointerException.class, () -> mapper.toJpaEntity(null));
    }

    @Test
    void rejectsNullEntity() {
        assertThrows(NullPointerException.class, () -> mapper.toDomain(null));
    }

    // --- helpers ---

    private Reservation pendingReservation() {
        return Reservation.reconstitute(
                ReservationId.of(RESERVATION_UUID),
                UserId.of(USER_UUID),
                SaleId.of(SALE_UUID),
                ProductId.of(PRODUCT_UUID),
                Quantity.of(2),
                new ReservationExpiry(EXPIRES_AT),
                new Status.Pending(),
                null,
                0L
        );
    }

    private Reservation confirmedReservation() {
        return Reservation.reconstitute(
                ReservationId.of(RESERVATION_UUID),
                UserId.of(USER_UUID),
                SaleId.of(SALE_UUID),
                ProductId.of(PRODUCT_UUID),
                Quantity.of(2),
                new ReservationExpiry(EXPIRES_AT),
                new Status.Confirmed(),
                OrderId.of(ORDER_UUID),
                1L
        );
    }

    private ReservationJpaEntity jpaEntity(String status, UUID orderId, long version) {
        return new ReservationJpaEntity(
                RESERVATION_UUID,
                USER_UUID,
                SALE_UUID,
                PRODUCT_UUID,
                status,
                2,
                EXPIRES_AT,
                orderId,
                version
        );
    }
}
