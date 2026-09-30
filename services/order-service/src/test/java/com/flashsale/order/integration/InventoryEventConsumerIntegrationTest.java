package com.flashsale.order.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("infrastructure")
class InventoryEventConsumerIntegrationTest {

    private static final String TOPIC = "inventory-events";
    private static final String GROUP = "order-svc-reservation-consumer";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
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

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ConsumerFactory<?, ?> consumerFactory;
    @Autowired private ConcurrentKafkaListenerContainerFactory<?, ?> listenerContainerFactory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("order.outbox.enabled", () -> false);
        registry.add("order.inventory-consumer.auto-startup", () -> true);
    }

    @Test
    void consumerUsesManualCommitConfiguration() {
        assertEquals(false, consumerFactory.getConfigurationProperties()
                .get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals(ContainerProperties.AckMode.MANUAL,
                listenerContainerFactory.getContainerProperties().getAckMode());
    }

    @Test
    void recordsStockReservedOnceUnderDuplicateDelivery() {
        UUID reservationId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID marker = UUID.randomUUID();

        send(stockReserved(reservationId, saleId, userId, 2, "2099-01-01T00:10:00Z"));
        awaitRow(reservationId);
        Timestamp firstReceivedAt = receivedAt(reservationId);
        send(stockReserved(reservationId, UUID.randomUUID(), UUID.randomUUID(), 5, "2099-12-31T00:00:00Z"));
        send(stockReserved(marker, saleId, userId, 1, "2099-01-01T00:10:00Z"));
        awaitRow(marker);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM purchase_intents WHERE purchase_intent_id = ?", reservationId);
        assertEquals(1, count(reservationId));
        assertEquals(firstReceivedAt, row.get("received_at"));
        assertEquals(userId, row.get("user_id"));
        assertEquals(saleId, row.get("sale_id"));
        assertEquals(2, row.get("quantity"));
        assertEquals(Instant.parse("2099-01-01T00:10:00Z"), ((Timestamp) row.get("valid_until")).toInstant());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    @Test
    void recordsAlreadyExpiredIntentAsReceived() {
        UUID reservationId = UUID.randomUUID();

        send(stockReserved(reservationId, UUID.randomUUID(), UUID.randomUUID(), 1, "2000-01-01T00:00:00Z"));
        awaitRow(reservationId);

        assertEquals(Instant.parse("2000-01-01T00:00:00Z"), jdbcTemplate.queryForObject(
                "SELECT valid_until FROM purchase_intents WHERE purchase_intent_id = ?",
                Timestamp.class, reservationId).toInstant());
    }

    @Test
    void poisonAndOtherEventTypesAreAcknowledgedAndDoNotBlockThePartition() throws Exception {
        UUID ignored = UUID.randomUUID();
        UUID marker = UUID.randomUUID();

        send("not json");
        send(stockReserved(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, "2099-01-01T00:00:00Z"));
        send(stockReserved(ignored, UUID.randomUUID(), UUID.randomUUID(), 1, "2099-01-01T00:00:00Z")
                .replace("StockReserved", "ReservationExpired"));
        send(stockReserved(marker, UUID.randomUUID(), UUID.randomUUID(), 1, "2099-01-01T00:00:00Z"));
        awaitRow(marker);

        assertEquals(0, count(ignored));
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            long end = admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                    .partitionResult(partition).get().offset();
            await().atMost(TIMEOUT).until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                        .partitionsToOffsetAndMetadata().get().get(partition);
                return committed != null && committed.offset() == end;
            });
        }
    }

    @Test
    void databaseFailureIsRetriedWithoutSkippingTheRecord() throws Exception {
        UUID blocked = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            jdbcTemplate.execute(
                    "ALTER TABLE purchase_intents ADD CONSTRAINT test_block_writes CHECK (false) NOT VALID");
            try {
                long offset = kafkaTemplate.send(TOPIC, "product", stockReserved(
                                blocked, UUID.randomUUID(), UUID.randomUUID(), 1, "2099-01-01T00:00:00Z"))
                        .join().getRecordMetadata().offset();
                // Spring Kafka's default handler (10 attempts, re-polled at most every 500 ms) skips the
                // record within about 5 s; stay blocked well past that so a skip would lose the row.
                await().during(Duration.ofSeconds(12)).atMost(Duration.ofSeconds(20))
                        .until(() -> count(blocked) == 0 && committedOffset(admin) <= offset);
            } finally {
                jdbcTemplate.execute("ALTER TABLE purchase_intents DROP CONSTRAINT test_block_writes");
            }

            awaitRow(blocked);
            send(stockReserved(next, UUID.randomUUID(), UUID.randomUUID(), 1, "2099-01-01T00:00:00Z"));
            awaitRow(next);
            long end = admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest()))
                    .partitionResult(PARTITION).get().offset();
            await().atMost(TIMEOUT).until(() -> committedOffset(admin) == end);
        }
    }

    private static long committedOffset(AdminClient admin) throws Exception {
        OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                .partitionsToOffsetAndMetadata().get().get(PARTITION);
        return committed == null ? -1 : committed.offset();
    }

    private Timestamp receivedAt(UUID purchaseIntentId) {
        return jdbcTemplate.queryForObject(
                "SELECT received_at FROM purchase_intents WHERE purchase_intent_id = ?",
                Timestamp.class, purchaseIntentId);
    }

    private void send(String value) {
        kafkaTemplate.send(TOPIC, "product", value).join();
    }

    private void awaitRow(UUID purchaseIntentId) {
        await().atMost(TIMEOUT).until(() -> count(purchaseIntentId) == 1);
    }

    private int count(UUID purchaseIntentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_intents WHERE purchase_intent_id = ?",
                Integer.class, purchaseIntentId);
    }

    private static String stockReserved(UUID reservationId, UUID saleId, UUID userId, int quantity, String expiresAt) {
        return """
                {"eventId":"%s","eventType":"StockReserved","eventVersion":"1.0",
                 "occurredAt":"2026-09-30T12:00:00Z","aggregateId":"%2$s","aggregateType":"Reservation",
                 "payload":{"reservationId":"%2$s","saleId":"%3$s","productId":"%6$s",
                            "userId":"%4$s","quantity":%5$d,"remainingStock":10,"expiresAt":"%7$s"}}
                """.formatted(UUID.randomUUID(), reservationId, saleId, userId, quantity,
                UUID.randomUUID(), expiresAt);
    }
}
