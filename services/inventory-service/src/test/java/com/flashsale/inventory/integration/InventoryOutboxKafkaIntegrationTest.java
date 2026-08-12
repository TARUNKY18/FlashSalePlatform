package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.inventory.infra.kafka.InventoryOutboxPublisher;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.scheduling.TaskScheduler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Import(InventoryOutboxKafkaIntegrationTest.StandaloneRedisConfiguration.class)
class InventoryOutboxKafkaIntegrationTest {

    @MockBean
    private TaskScheduler taskScheduler;

    private static final String TOPIC = "inventory-events";
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.3-alpine"))
                    .withDatabaseName("inventory_db")
                    .withUsername("flashsale")
                    .withPassword("flashsale_dev");
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2.5-alpine"))
                    .withExposedPorts(6379);
    @SuppressWarnings("deprecation")
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"))
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        Startables.deepStart(Stream.of(POSTGRES, REDIS, KAFKA)).join();
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InventoryOutboxPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("inventory.kafka.topic.replication-factor", () -> 1);
        registry.add("inventory.kafka.topic.min-in-sync-replicas", () -> 1);
    }

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM inventory_outbox");
        jdbcTemplate.update("DELETE FROM stock_reservation_log");
        jdbcTemplate.update("DELETE FROM reservations");
        jdbcTemplate.update("DELETE FROM stock_levels");
        jdbcTemplate.update("DELETE FROM products");
    }

    @Test
    void singleBrokerTopicUsesFrozenTestEquivalent() throws Exception {
        try (AdminClient admin = adminClient()) {
            DescribeTopicsResult described = admin.describeTopics(List.of(TOPIC));
            assertEquals(16, described.allTopicNames().get(10, TimeUnit.SECONDS)
                    .get(TOPIC).partitions().size());

            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, TOPIC);
            Config config = admin.describeConfigs(List.of(resource)).all()
                    .get(10, TimeUnit.SECONDS).get(resource);
            assertEquals("259200000", value(config, TopicConfig.RETENTION_MS_CONFIG));
            assertEquals("lz4", value(config, TopicConfig.COMPRESSION_TYPE_CONFIG));
            assertEquals("1", value(config, TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG));
        }
    }

    @Test
    void storedEnvelopePublishesWithProductIdKeyAndMarksRowOnlyAfterAck() throws Exception {
        UUID eventId = insertOutbox();

        publisher.publishPendingBatch();

        ConsumerRecord<String, String> record = awaitEvent(eventId, 1).getFirst();
        JsonNode envelope = objectMapper.readTree(record.value());
        assertEquals(productId(eventId).toString(), record.key());
        assertEquals(eventId.toString(), envelope.get("eventId").asText());
        assertEquals("StockReserved", envelope.get("eventType").asText());
        assertEquals("1.0", envelope.get("eventVersion").asText());
        assertEquals("Reservation", envelope.get("aggregateType").asText());
        assertEquals(7, envelope.get("payload").size());
        assertFalse(envelope.get("payload").has("source"));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT published FROM inventory_outbox WHERE event_id = ?",
                Boolean.class, eventId));
    }

    @Test
    void crashWindowRedeliveryReusesTheStoredEventId() throws Exception {
        UUID eventId = insertOutbox();
        publisher.publishPendingBatch();
        awaitEvent(eventId, 1);

        jdbcTemplate.update(
                "UPDATE inventory_outbox SET published = FALSE, published_at = NULL WHERE event_id = ?",
                eventId);
        publisher.publishPendingBatch();

        List<ConsumerRecord<String, String>> deliveries = awaitEvent(eventId, 2);
        assertTrue(deliveries.size() >= 2);
        for (ConsumerRecord<String, String> delivery : deliveries) {
            assertEquals(eventId.toString(),
                    objectMapper.readTree(delivery.value()).get("eventId").asText());
        }
    }

    @Test
    void realKafkaOutageRetainsRowAndSameEndpointRecoveryPublishesStableEventId()
            throws Exception {
        UUID eventId = insertOutbox();
        String bootstrapServers = KAFKA.getBootstrapServers();
        String containerId = KAFKA.getContainerId();
        assertEquals(16, kafkaTemplate.partitionsFor(TOPIC).size());

        KAFKA.getDockerClient().pauseContainerCmd(containerId).exec();
        try {
            publisher.publishPendingBatch();

            assertFalse(jdbcTemplate.queryForObject(
                    "SELECT published FROM inventory_outbox WHERE event_id = ?",
                    Boolean.class, eventId));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM inventory_outbox WHERE event_id = ?",
                    Integer.class, eventId));
            assertTrue(jdbcTemplate.queryForObject(
                    "SELECT last_error FROM inventory_outbox WHERE event_id = ?",
                    String.class, eventId).contains("TimeoutException"));
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(containerId).exec();
        }

        awaitBrokerRecovery(bootstrapServers);
        assertEquals(containerId, KAFKA.getContainerId());
        assertEquals(bootstrapServers, KAFKA.getBootstrapServers());

        publisher.publishPendingBatch();

        List<ConsumerRecord<String, String>> deliveries = awaitEvent(eventId, 1);
        for (ConsumerRecord<String, String> delivery : deliveries) {
            assertEquals(eventId.toString(),
                    objectMapper.readTree(delivery.value()).get("eventId").asText());
        }
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT published FROM inventory_outbox WHERE event_id = ?",
                Boolean.class, eventId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM inventory_outbox WHERE event_id = ?",
                Integer.class, eventId));
    }

    private UUID insertOutbox() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID productId = productId(eventId);
        UUID saleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO products (id, total_stock, version) VALUES (?, 100, 0)",
                productId);
        jdbcTemplate.update(
                """
                INSERT INTO reservations (
                    id, user_id, sale_id, product_id, status, quantity,
                    expires_at, idempotency_key, version
                ) VALUES (?, ?, ?, ?, 'PENDING', 1, ?, ?, 0)
                """,
                reservationId, userId, saleId, productId,
                Timestamp.from(Instant.parse("2026-08-11T12:10:00Z")),
                UUID.randomUUID().toString());
        String payload = """
                {"reservationId":"%s","saleId":"%s","productId":"%s","userId":"%s",
                 "quantity":1,"remainingStock":99,"expiresAt":"2026-08-11T12:10:00Z"}
                """.formatted(reservationId, saleId, productId, userId);
        jdbcTemplate.update(
                """
                INSERT INTO inventory_outbox (
                    id, reservation_id, event_id, event_type, event_version,
                    aggregate_id, aggregate_type, payload, occurred_at
                ) VALUES (?, ?, ?, 'StockReserved', '1.0', ?, 'Reservation', CAST(? AS JSONB), ?)
                """,
                UUID.randomUUID(), reservationId, eventId, reservationId, payload,
                Timestamp.from(Instant.parse("2026-08-11T12:00:00Z")));
        return eventId;
    }

    private List<ConsumerRecord<String, String>> awaitEvent(UUID eventId, int minimumCount) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "slice5-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Set.of(TOPIC));
            java.util.ArrayList<ConsumerRecord<String, String>> matches = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && matches.size() < minimumCount) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        if (eventId.toString().equals(
                                objectMapper.readTree(record.value()).get("eventId").asText())) {
                            matches.add(record);
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            }
            assertTrue(matches.size() >= minimumCount,
                    "Expected at least " + minimumCount + " delivery/deliveries for " + eventId);
            return matches;
        }
    }

    private static UUID productId(UUID eventId) {
        return new UUID(eventId.getMostSignificantBits(), eventId.getLeastSignificantBits() ^ 1L);
    }

    private static AdminClient adminClient() {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    private static void awaitBrokerRecovery(String bootstrapServers) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (AdminClient admin = AdminClient.create(Map.of(
                    AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
                admin.describeTopics(List.of(TOPIC)).allTopicNames().get(2, TimeUnit.SECONDS);
                return;
            } catch (Exception exception) {
                lastFailure = exception;
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Kafka did not recover on the original endpoint", lastFailure);
    }

    private static String value(Config config, String name) {
        ConfigEntry entry = config.get(name);
        return entry == null ? null : entry.value();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StandaloneRedisConfiguration {

        @Bean
        @Primary
        LettuceConnectionFactory inventoryKafkaTestRedisConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }
    }
}
