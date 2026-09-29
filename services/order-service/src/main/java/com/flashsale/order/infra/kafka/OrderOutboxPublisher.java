package com.flashsale.order.infra.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.infra.persistence.OrderOutboxJpaEntity;
import com.flashsale.order.infra.persistence.SpringDataOrderOutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Publishes durable order outbox rows with at-least-once delivery. */
@Component
@Profile("infrastructure")
@ConditionalOnProperty(name = "order.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OrderOutboxPublisher {

    static final String TOPIC = "order-events";
    private static final long BATCH_WAIT_SECONDS = 10L;
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderOutboxPublisher.class);

    private final SpringDataOrderOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderOutboxPublisher(
            SpringDataOrderOutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPendingBatch() {
        List<OrderOutboxJpaEntity> rows = outboxRepository.lockNextUnpublishedBatch();
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
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Order outbox publish interrupted; batch remains unpublished", exception);
            return;
        } catch (Exception exception) {
            LOGGER.warn("Order outbox publish failed; batch remains unpublished", exception);
            return;
        }

        Instant publishedAt = Instant.now();
        rows.forEach(row -> row.markPublished(publishedAt));
        outboxRepository.saveAllAndFlush(rows);
    }

    private List<Publication> prepare(List<OrderOutboxJpaEntity> rows)
            throws JsonProcessingException {
        List<Publication> publications = new ArrayList<>(rows.size());
        for (OrderOutboxJpaEntity row : rows) {
            if (!"OrderCreated".equals(row.getEventType())) {
                throw new IllegalArgumentException(
                        "unsupported persisted event_type: " + row.getEventType());
            }
            JsonNode payload = Objects.requireNonNull(row.getPayload(), "payload must not be null");
            JsonNode saleIdNode = payload.get("saleId");
            if (saleIdNode == null || !saleIdNode.isTextual()) {
                throw new IllegalArgumentException("payload.saleId must be a UUID string");
            }
            String key = UUID.fromString(saleIdNode.textValue()).toString();
            OrderEventEnvelope envelope = new OrderEventEnvelope(
                    row.getEventId(),
                    row.getEventType(),
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

    private record Publication(String key, String envelope) {}

    private record OrderEventEnvelope(
            UUID eventId,
            String eventType,
            String eventVersion,
            Instant occurredAt,
            UUID aggregateId,
            String aggregateType,
            JsonNode payload
    ) {}
}
