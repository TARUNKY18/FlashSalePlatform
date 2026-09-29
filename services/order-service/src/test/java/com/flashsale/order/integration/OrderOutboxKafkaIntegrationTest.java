package com.flashsale.order.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.infra.kafka.OrderOutboxPublisher;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("infrastructure")
class OrderOutboxKafkaIntegrationTest {

    private static final String TOPIC = "order-events";
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T12:00:00Z");
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.3-alpine"))
                    .withDatabaseName("orders_db")
                    .withUsername("flashsale")
                    .withPassword("flashsale_dev");
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    @MockBean private TaskScheduler taskScheduler;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderOutboxPublisher publisher;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM order_outbox");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
    }

    @Test
    void publishesStoredEnvelopeToKafkaAndMarksRowAfterAcknowledgement() throws Exception {
        Event event = insertOutbox();

        publisher.publishPendingBatch();

        ConsumerRecord<String, String> record = awaitEvents(Set.of(event.eventId())).getFirst();
        JsonNode envelope = objectMapper.readTree(record.value());
        assertEquals(event.saleId().toString(), record.key());
        assertEquals(event.eventId().toString(), envelope.get("eventId").asText());
        assertEquals("OrderCreated", envelope.get("eventType").asText());
        assertEquals(6, envelope.get("payload").size());
        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT published FROM order_outbox WHERE event_id = ?",
                Boolean.class, event.eventId()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT published_at FROM order_outbox WHERE event_id = ?",
                Instant.class, event.eventId()));
    }

    @Test
    void onePollProcessesAtMostOneHundredRows() {
        for (int index = 0; index < 101; index++) {
            insertOutbox();
        }

        publisher.publishPendingBatch();

        assertEquals(100, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_outbox WHERE published", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_outbox WHERE NOT published", Integer.class));
    }

    @Test
    void threeConcurrentPollersDoNotPublishTheSameRowTwice() throws Exception {
        Set<UUID> eventIds = new HashSet<>();
        for (int index = 0; index < 12; index++) {
            eventIds.add(insertOutbox().eventId());
        }
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int poller = 0; poller < 3; poller++) {
                calls.add(executor.submit(() -> {
                    start.await();
                    publisher.publishPendingBatch();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get();
            }
        }

        List<ConsumerRecord<String, String>> records = awaitEvents(eventIds);
        assertEquals(12, records.size());
        assertEquals(12, records.stream()
                .map(record -> eventId(record.value()))
                .distinct()
                .count());
        assertEquals(12, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_outbox WHERE published", Integer.class));
    }

    private Event insertOutbox() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Timestamp at = Timestamp.from(
                CREATED_AT.plusNanos(Integer.toUnsignedLong(eventId.hashCode())));
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    id, user_id, sale_id, reservation_id, status, amount, currency,
                    idempotency_key, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 19.90, 'USD', ?, 0, ?, ?)
                """,
                orderId, userId, saleId, reservationId, eventId.toString(), at, at);
        String payload = """
                {"orderId":"%s","reservationId":"%s","userId":"%s",
                 "saleId":"%s","amount":"19.90","currency":"USD"}
                """.formatted(orderId, reservationId, userId, saleId);
        jdbcTemplate.update(
                """
                INSERT INTO order_outbox (
                    id, order_id, event_id, event_type, event_version, occurred_at,
                    aggregate_id, aggregate_type, payload, created_at
                ) VALUES (?, ?, ?, 'OrderCreated', '1.0', ?, ?, 'Order', CAST(? AS JSONB), ?)
                """,
                UUID.randomUUID(), orderId, eventId, at, orderId, payload, at);
        return new Event(eventId, saleId);
    }

    private List<ConsumerRecord<String, String>> awaitEvents(Set<UUID> eventIds) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "task-6-1-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Set.of(TOPIC));
            List<ConsumerRecord<String, String>> matches = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && matches.size() < eventIds.size()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    if (eventIds.contains(eventId(record.value()))) {
                        matches.add(record);
                    }
                }
            }
            return matches;
        }
    }

    private UUID eventId(String envelope) {
        try {
            return UUID.fromString(objectMapper.readTree(envelope).get("eventId").asText());
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid event envelope", exception);
        }
    }

    private record Event(UUID eventId, UUID saleId) {}
}
