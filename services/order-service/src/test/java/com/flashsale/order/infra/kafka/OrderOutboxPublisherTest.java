package com.flashsale.order.infra.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flashsale.order.infra.persistence.OrderOutboxJpaEntity;
import com.flashsale.order.infra.persistence.SpringDataOrderOutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OrderOutboxPublisherTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-29T12:00:00Z");

    private final SpringDataOrderOutboxRepository repository =
            mock(SpringDataOrderOutboxRepository.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final OrderOutboxPublisher publisher =
            new OrderOutboxPublisher(repository, kafkaTemplate, objectMapper);

    @Test
    void emptyBatchDoesNothing() {
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of());

        publisher.publishPendingBatch();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void acknowledgedBatchPublishesExactEnvelopeBySaleAndMarksRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        OrderOutboxJpaEntity row = row(eventId, saleId, "OrderCreated");
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPendingBatch();

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("order-events"),
                key.capture(), envelope.capture());
        assertEquals(saleId.toString(), key.getValue());
        assertEquals(eventId.toString(),
                objectMapper.readTree(envelope.getValue()).get("eventId").asText());
        assertEquals("OrderCreated",
                objectMapper.readTree(envelope.getValue()).get("eventType").asText());
        assertFalse(objectMapper.readTree(envelope.getValue()).has("traceId"));
        verify(row).markPublished(any(Instant.class));
        verify(repository).saveAllAndFlush(List.of(row));
    }

    @Test
    void failedPublishLeavesWholeBatchUnpublishedForRetry() {
        OrderOutboxJpaEntity first = row(UUID.randomUUID(), UUID.randomUUID(), "OrderCreated");
        OrderOutboxJpaEntity second = row(UUID.randomUUID(), UUID.randomUUID(), "OrderCreated");
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("Kafka unavailable"));
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)), failed);

        publisher.publishPendingBatch();

        verify(first, never()).markPublished(any());
        verify(second, never()).markPublished(any());
        verify(repository, never()).saveAllAndFlush(any());
    }

    @Test
    void waitsForBrokerAcknowledgementBeforeMarkingPublished() throws Exception {
        OrderOutboxJpaEntity row = row(UUID.randomUUID(), UUID.randomUUID(), "OrderCreated");
        CompletableFuture<SendResult<String, String>> acknowledgement = new CompletableFuture<>();
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(row));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(acknowledgement);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var publication = executor.submit(publisher::publishPendingBatch);
            verify(kafkaTemplate, org.mockito.Mockito.timeout(1_000))
                    .send(anyString(), anyString(), anyString());
            verify(row, never()).markPublished(any());

            acknowledgement.complete(mock(SendResult.class));
            publication.get();
        }

        verify(row).markPublished(any(Instant.class));
    }

    @Test
    void retryPublishesTheSameStoredEventId() throws Exception {
        UUID eventId = UUID.randomUUID();
        OrderOutboxJpaEntity row = row(eventId, UUID.randomUUID(), "OrderCreated");
        CompletableFuture<SendResult<String, String>> failure = new CompletableFuture<>();
        failure.completeExceptionally(new IllegalStateException("Kafka unavailable"));
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(row), List.of(row));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failure, CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishPendingBatch();
        publisher.publishPendingBatch();

        ArgumentCaptor<String> envelopes = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, org.mockito.Mockito.times(2))
                .send(anyString(), anyString(), envelopes.capture());
        assertEquals(2, envelopes.getAllValues().size());
        assertTrue(envelopes.getAllValues().stream().allMatch(envelope -> {
            try {
                return eventId.toString().equals(
                        objectMapper.readTree(envelope).get("eventId").asText());
            } catch (Exception exception) {
                return false;
            }
        }));
    }

    @Test
    void unsupportedPersistedTypeRejectsWholeBatchBeforeSending() {
        OrderOutboxJpaEntity valid = row(UUID.randomUUID(), UUID.randomUUID(), "OrderCreated");
        OrderOutboxJpaEntity unknown = row(UUID.randomUUID(), UUID.randomUUID(), "OrderConfirmed");
        when(repository.lockNextUnpublishedBatch()).thenReturn(List.of(valid, unknown));

        publisher.publishPendingBatch();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(valid, never()).markPublished(any());
        verify(unknown, never()).markPublished(any());
    }

    private OrderOutboxJpaEntity row(UUID eventId, UUID saleId, String eventType) {
        OrderOutboxJpaEntity row = mock(OrderOutboxJpaEntity.class);
        UUID orderId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("orderId", orderId.toString())
                .put("reservationId", UUID.randomUUID().toString())
                .put("userId", UUID.randomUUID().toString())
                .put("saleId", saleId.toString())
                .put("amount", "19.90")
                .put("currency", "USD");
        when(row.getEventId()).thenReturn(eventId);
        when(row.getEventType()).thenReturn(eventType);
        when(row.getEventVersion()).thenReturn("1.0");
        when(row.getOccurredAt()).thenReturn(OCCURRED_AT);
        when(row.getAggregateId()).thenReturn(orderId);
        when(row.getAggregateType()).thenReturn("Order");
        when(row.getPayload()).thenReturn(payload);
        return row;
    }
}
