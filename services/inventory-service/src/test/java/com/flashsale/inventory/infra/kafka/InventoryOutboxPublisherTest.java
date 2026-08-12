package com.flashsale.inventory.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flashsale.inventory.infra.persistence.InventoryOutboxJpaEntity;
import com.flashsale.inventory.infra.persistence.SpringDataInventoryOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class InventoryOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    private final SpringDataInventoryOutboxRepository repository = mock(
            SpringDataInventoryOutboxRepository.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final InventoryOutboxPublisher publisher = new InventoryOutboxPublisher(
            repository, kafkaTemplate, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void emptyBatchDoesNotPublish() {
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of());

        publisher.publishPendingBatch();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void successfulBatchPublishesStoredEnvelopeByProductAndMarksEveryRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        InventoryOutboxJpaEntity row = row(eventId, productId);
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPendingBatch();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("inventory-events"), key.capture(), json.capture());
        assertEquals(productId.toString(), key.getValue());
        assertEquals(eventId.toString(), objectMapper.readTree(json.getValue()).get("eventId").asText());
        assertEquals("StockReserved", objectMapper.readTree(json.getValue()).get("eventType").asText());
        assertFalse(objectMapper.readTree(json.getValue()).get("payload").has("source"));
        assertFalse(objectMapper.readTree(json.getValue()).has("traceId"));
        verify(row).markPublished(NOW);
        verify(row, never()).recordFailure(any(), anyString());
        verify(repository).saveAllAndFlush(List.of(row));
    }

    @Test
    void supportedPersistedEventTypesAreAccepted() {
        InventoryOutboxJpaEntity stockReserved =
                row(UUID.randomUUID(), UUID.randomUUID(), "StockReserved");
        InventoryOutboxJpaEntity reservationExpired =
                row(UUID.randomUUID(), UUID.randomUUID(), "ReservationExpired");
        when(repository.lockNextUnpublishedBatch())
                .thenReturn(List.of(stockReserved, reservationExpired));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPendingBatch();

        verify(kafkaTemplate, org.mockito.Mockito.times(2))
                .send(anyString(), anyString(), anyString());
        verify(stockReserved).markPublished(NOW);
        verify(reservationExpired).markPublished(NOW);
    }

    @Test
    void unknownPersistedEventTypeRejectsWholeBatchBeforeFirstSend() {
        InventoryOutboxJpaEntity valid =
                row(UUID.randomUUID(), UUID.randomUUID(), "StockReserved");
        InventoryOutboxJpaEntity unknown =
                row(UUID.randomUUID(), UUID.randomUUID(), "InventoryAdjusted");
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(valid, unknown));

        publisher.publishPendingBatch();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(valid).recordFailure(NOW,
                "IllegalArgumentException: unsupported persisted event_type: InventoryAdjusted");
        verify(unknown).recordFailure(NOW,
                "IllegalArgumentException: unsupported persisted event_type: InventoryAdjusted");
        verify(valid, never()).markPublished(any());
        verify(unknown, never()).markPublished(any());
        verify(repository).saveAllAndFlush(List.of(valid, unknown));
    }

    @Test
    void oneKafkaFailureRecordsOneFailureForEverySelectedRowAndMarksNonePublished() {
        InventoryOutboxJpaEntity first = row(UUID.randomUUID(), UUID.randomUUID());
        InventoryOutboxJpaEntity second = row(UUID.randomUUID(), UUID.randomUUID());
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(first, second));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("Kafka unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)), failed);

        publisher.publishPendingBatch();

        verify(first).recordFailure(NOW, "IllegalStateException: Kafka unavailable");
        verify(second).recordFailure(NOW, "IllegalStateException: Kafka unavailable");
        verify(first, never()).markPublished(any());
        verify(second, never()).markPublished(any());
        verify(repository).saveAllAndFlush(List.of(first, second));
    }

    @Test
    void invalidStoredPayloadFailsWholeBatchBeforeAnySend() {
        InventoryOutboxJpaEntity row = row(UUID.randomUUID(), UUID.randomUUID());
        when(row.getPayload()).thenReturn(objectMapper.createObjectNode());
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(row));

        publisher.publishPendingBatch();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(row).recordFailure(NOW, "IllegalArgumentException: payload.productId must be a UUID string");
    }

    @Test
    void recoveryRedeliversTheIdenticalStoredEventId() throws Exception {
        UUID eventId = UUID.randomUUID();
        InventoryOutboxJpaEntity row = row(eventId, UUID.randomUUID());
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(row), List.of(row));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failed, CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPendingBatch();
        publisher.publishPendingBatch();

        ArgumentCaptor<String> envelopes = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2))
                .send(anyString(), anyString(), envelopes.capture());
        assertEquals(eventId.toString(), objectMapper.readTree(
                envelopes.getAllValues().get(0)).get("eventId").asText());
        assertEquals(eventId.toString(), objectMapper.readTree(
                envelopes.getAllValues().get(1)).get("eventId").asText());
        InOrder order = inOrder(row);
        order.verify(row).recordFailure(any(), anyString());
        order.verify(row).markPublished(NOW);
    }

    @Test
    void batchAcknowledgementTimeoutRecordsFailureAndLeavesRowUnpublished() {
        InventoryOutboxJpaEntity row = row(UUID.randomUUID(), UUID.randomUUID());
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        assertTimeout(Duration.ofSeconds(12), publisher::publishPendingBatch);

        verify(row).recordFailure(
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.contains("TimeoutException"));
        verify(row, never()).markPublished(any());
    }

    private InventoryOutboxJpaEntity row(UUID eventId, UUID productId) {
        return row(eventId, productId, "StockReserved");
    }

    private InventoryOutboxJpaEntity row(UUID eventId, UUID productId, String eventType) {
        InventoryOutboxJpaEntity row = mock(InventoryOutboxJpaEntity.class);
        UUID reservationId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("reservationId", reservationId.toString())
                .put("saleId", UUID.randomUUID().toString())
                .put("productId", productId.toString())
                .put("userId", UUID.randomUUID().toString())
                .put("quantity", 1)
                .put("remainingStock", 9)
                .put("expiresAt", "2026-08-11T12:10:00Z");
        when(row.getEventId()).thenReturn(eventId);
        when(row.getEventType()).thenReturn(eventType);
        when(row.getEventVersion()).thenReturn("1.0");
        when(row.getOccurredAt()).thenReturn(NOW);
        when(row.getAggregateId()).thenReturn(reservationId);
        when(row.getAggregateType()).thenReturn("Reservation");
        when(row.getPayload()).thenReturn(payload);
        return row;
    }
}
