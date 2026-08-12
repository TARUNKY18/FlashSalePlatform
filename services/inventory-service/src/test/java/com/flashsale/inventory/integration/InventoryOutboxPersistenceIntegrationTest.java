package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashsale.inventory.application.InventoryEvent;
import com.flashsale.inventory.application.port.ReservationRepository;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import com.flashsale.inventory.infra.persistence.InventoryOutboxJpaEntity;
import com.flashsale.inventory.infra.persistence.SpringDataInventoryOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class InventoryOutboxPersistenceIntegrationTest extends InventoryInfrastructureTestSupport {

    private static final UUID PRODUCT_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID STOCK_LEVEL_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000002");
    private static final UUID SALE_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000003");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-11T12:00:00Z");

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private SpringDataInventoryOutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void insertStock() {
        insertProductWithStock(PRODUCT_ID, STOCK_LEVEL_ID, SALE_ID, 100, 0, 100, 100, 0);
    }

    @Test
    void v4HasFrozenColumnsConstraintsDefaultsAndIndexes() {
        Map<String, String> columns = jdbcTemplate.query(
                """
                SELECT column_name, data_type, is_nullable, COALESCE(column_default, '')
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'inventory_outbox'
                ORDER BY ordinal_position
                """,
                resultSet -> {
                    java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getString(1), resultSet.getString(2) + "|"
                                + resultSet.getString(3) + "|" + resultSet.getString(4));
                    }
                    return values;
                });

        assertEquals(15, columns.size());
        assertEquals("uuid|NO|", columns.get("id"));
        assertEquals("uuid|NO|", columns.get("event_id"));
        assertEquals("integer|NO|0", columns.get("attempt_count"));
        assertTrue(columns.get("published").endsWith("|false"));
        assertTrue(columns.get("created_at").contains("now()"));

        String indexes = String.join("\n", jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'inventory_outbox'",
                String.class));
        assertTrue(indexes.contains("idx_inventory_outbox_unpublished"));
        assertTrue(indexes.contains("WHERE (published = false)"));
        assertTrue(indexes.contains("idx_inventory_outbox_reservation_id"));
    }

    @Test
    void reservationAndStockReservedOutboxArePersistedAtomicallyWithExactPayload() {
        Reservation reservation = reservation(UUID.randomUUID());
        UUID eventId = UUID.randomUUID();
        InventoryEvent.StockReserved event = new InventoryEvent.StockReserved(
                eventId, OCCURRED_AT, reservation.id(), reservation.saleId(),
                reservation.productId(), reservation.userId(), 1, 99,
                reservation.expiry().expiresAt());

        reservationRepository.saveWithOutboxEvent(reservation, event);

        InventoryOutboxJpaEntity row = outboxRepository.findAll().getFirst();
        assertNotNull(row.getId());
        assertEquals(reservation.id().value(), row.getReservationId());
        assertEquals(eventId, row.getEventId());
        assertEquals("StockReserved", row.getEventType());
        assertEquals("1.0", row.getEventVersion());
        assertEquals(reservation.id().value(), row.getAggregateId());
        assertEquals("Reservation", row.getAggregateType());
        assertEquals(OCCURRED_AT, row.getOccurredAt());
        assertFalse(row.isPublished());
        assertEquals(0, row.getAttemptCount());
        assertNull(row.getPublishedAt());
        assertNull(row.getLastAttemptedAt());
        assertNull(row.getLastError());
        assertNotNull(row.getCreatedAt());
        JsonNode payload = row.getPayload();
        assertEquals(7, payload.size());
        assertEquals(reservation.id().toString(), payload.get("reservationId").asText());
        assertEquals(PRODUCT_ID.toString(), payload.get("productId").asText());
        assertEquals(99, payload.get("remainingStock").asInt());
        assertFalse(payload.has("source"));
        assertFalse(payload.has("traceId"));
    }

    @Test
    void duplicateEventIdRollsBackTheSecondReservationAndOutboxTogether() {
        UUID eventId = UUID.randomUUID();
        Reservation first = reservation(UUID.randomUUID());
        reservationRepository.saveWithOutboxEvent(first, event(first, eventId));
        Reservation second = reservation(UUID.randomUUID());

        assertThrows(DataIntegrityViolationException.class,
                () -> reservationRepository.saveWithOutboxEvent(second, event(second, eventId)));

        assertTrue(reservationRepository.findById(second.id()).isEmpty());
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void foreignKeyRestrictsDeletingReservationWithOutboxHistory() {
        Reservation reservation = reservation(UUID.randomUUID());
        reservationRepository.saveWithOutboxEvent(
                reservation, event(reservation, UUID.randomUUID()));

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM reservations WHERE id = ?",
                        reservation.id().value()));
    }

    @Test
    void failureAndLaterSuccessRetainLastErrorWithoutIncrementingOnSuccess() {
        Reservation reservation = reservation(UUID.randomUUID());
        reservationRepository.saveWithOutboxEvent(
                reservation, event(reservation, UUID.randomUUID()));
        InventoryOutboxJpaEntity row = outboxRepository.findAll().getFirst();
        Instant failedAt = OCCURRED_AT.plusSeconds(1);
        Instant successAt = OCCURRED_AT.plusSeconds(2);

        row.recordFailure(failedAt, "Kafka unavailable");
        outboxRepository.saveAndFlush(row);
        row.markPublished(successAt);
        outboxRepository.saveAndFlush(row);

        InventoryOutboxJpaEntity reloaded = outboxRepository.findById(row.getId()).orElseThrow();
        assertTrue(reloaded.isPublished());
        assertEquals(successAt, reloaded.getPublishedAt());
        assertEquals(successAt, reloaded.getLastAttemptedAt());
        assertEquals(1, reloaded.getAttemptCount());
        assertEquals("Kafka unavailable", reloaded.getLastError());
    }

    @Test
    void reservationExpiredPayloadUsesContractualExpiryAndOmitsRestorationState() {
        Reservation reservation = reservation(UUID.randomUUID());
        reservationRepository.save(reservation);
        reservation.expire();
        InventoryEvent.ReservationExpired event = new InventoryEvent.ReservationExpired(
                UUID.randomUUID(), OCCURRED_AT.plusSeconds(20), reservation.id(),
                reservation.saleId(), reservation.productId(), reservation.userId(),
                reservation.quantity().value(), reservation.expiry().expiresAt());

        reservationRepository.saveWithOutboxEvent(reservation, event);

        JsonNode payload = outboxRepository.findAll().getFirst().getPayload();
        assertEquals(6, payload.size());
        assertEquals(reservation.expiry().expiresAt().toString(), payload.get("expiredAt").asText());
        assertFalse(payload.has("stockRestored"));
    }

    @Test
    void expiryAndOutboxFailureRollBackTheExpiryTransitionTogether() {
        UUID duplicateEventId = UUID.randomUUID();
        Reservation first = reservation(UUID.randomUUID());
        reservationRepository.saveWithOutboxEvent(first, event(first, duplicateEventId));
        Reservation expiring = reservation(UUID.randomUUID());
        reservationRepository.save(expiring);
        expiring.expire();
        InventoryEvent.ReservationExpired duplicate = new InventoryEvent.ReservationExpired(
                duplicateEventId, OCCURRED_AT.plusSeconds(20), expiring.id(), expiring.saleId(),
                expiring.productId(), expiring.userId(), expiring.quantity().value(),
                expiring.expiry().expiresAt());

        assertThrows(DataIntegrityViolationException.class,
                () -> reservationRepository.saveWithOutboxEvent(expiring, duplicate));

        Reservation reloaded = reservationRepository.findById(expiring.id()).orElseThrow();
        assertTrue(reloaded.status() instanceof Reservation.Status.Pending);
        assertEquals(1, outboxRepository.count());
    }

    @Test
    void skipLockedAllowsAnotherPodToPollWithoutWaitingOrRelocking() throws Exception {
        Reservation reservation = reservation(UUID.randomUUID());
        reservationRepository.saveWithOutboxEvent(
                reservation, event(reservation, UUID.randomUUID()));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(status -> {
                        assertEquals(1, outboxRepository.lockNextUnpublishedBatch().size());
                        locked.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    });
                } catch (Throwable throwable) {
                    workerFailure.set(throwable);
                    locked.countDown();
                }
            });
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            List<InventoryOutboxJpaEntity> competingBatch =
                    transaction.execute(status -> outboxRepository.lockNextUnpublishedBatch());

            assertNotNull(competingBatch);
            assertTrue(competingBatch.isEmpty());
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertNull(workerFailure.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private Reservation reservation(UUID userId) {
        return Reservation.create(
                UserId.of(userId), SaleId.of(SALE_ID), ProductId.of(PRODUCT_ID), Quantity.one(),
                new ReservationExpiry(OCCURRED_AT.plusSeconds(600)), OCCURRED_AT,
                UUID.randomUUID().toString());
    }

    private InventoryEvent.StockReserved event(Reservation reservation, UUID eventId) {
        return new InventoryEvent.StockReserved(
                eventId, OCCURRED_AT, reservation.id(), reservation.saleId(),
                reservation.productId(), reservation.userId(), reservation.quantity().value(),
                99, reservation.expiry().expiresAt());
    }
}
