package com.flashsale.inventory.infra.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.inventory.infra.persistence.InventoryOutboxJpaEntity;
import com.flashsale.inventory.infra.persistence.SpringDataInventoryOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Publishes durable inventory outbox rows with at-least-once delivery. */
@Component
public class InventoryOutboxPublisher {

    static final String TOPIC = "inventory-events";
    private static final long BATCH_WAIT_SECONDS = 10L;

    private final SpringDataInventoryOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InventoryOutboxPublisher(
            SpringDataInventoryOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPendingBatch() {
        List<InventoryOutboxJpaEntity> rows = outboxRepository.lockNextUnpublishedBatch();
        if (rows.isEmpty()) {
            return;
        }

        try {
            List<Publication> publications = prepare(rows);
            List<CompletableFuture<?>> sends = new ArrayList<>(publications.size());
            for (Publication publication : publications) {
                sends.add(kafkaTemplate.send(TOPIC, publication.key(), publication.envelope()));
            }
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(BATCH_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            Instant attemptedAt = clock.instant();
            String error = errorMessage(exception);
            rows.forEach(row -> row.recordFailure(attemptedAt, error));
            outboxRepository.saveAllAndFlush(rows);
            return;
        }

        Instant attemptedAt = clock.instant();
        rows.forEach(row -> row.markPublished(attemptedAt));
        outboxRepository.saveAllAndFlush(rows);
    }

    private List<Publication> prepare(List<InventoryOutboxJpaEntity> rows)
            throws JsonProcessingException {
        List<Publication> publications = new ArrayList<>(rows.size());
        for (InventoryOutboxJpaEntity row : rows) {
            String eventType = row.getEventType();
            if (!"StockReserved".equals(eventType)
                    && !"ReservationExpired".equals(eventType)) {
                throw new IllegalArgumentException(
                        "unsupported persisted event_type: " + eventType);
            }
            JsonNode payload = Objects.requireNonNull(row.getPayload(), "payload must not be null");
            JsonNode productIdNode = payload.get("productId");
            if (productIdNode == null || !productIdNode.isTextual()) {
                throw new IllegalArgumentException("payload.productId must be a UUID string");
            }
            String key = UUID.fromString(productIdNode.textValue()).toString();
            InventoryEventEnvelope envelope = new InventoryEventEnvelope(
                    row.getEventId(),
                    eventType,
                    row.getEventVersion(),
                    row.getOccurredAt(),
                    row.getAggregateId(),
                    row.getAggregateType(),
                    payload
            );
            publications.add(new Publication(key, objectMapper.writeValueAsString(envelope)));
        }
        return publications;
    }

    private static String errorMessage(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private record Publication(String key, String envelope) {
    }

    private record InventoryEventEnvelope(
            UUID eventId,
            String eventType,
            String eventVersion,
            Instant occurredAt,
            UUID aggregateId,
            String aggregateType,
            JsonNode payload
    ) {
    }
}
