package com.flashsale.order.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.application.port.OrderRepository;
import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("infrastructure")
@Testcontainers(disabledWithoutDocker = true)
class OrderOutboxPersistenceIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2099-01-01T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.3-alpine"))
                    .withDatabaseName("orders_db")
                    .withUsername("flashsale")
                    .withPassword("flashsale_dev");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("order.outbox.enabled", () -> false);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private OrderRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void clearRecords() {
        jdbcTemplate.update("DELETE FROM order_outbox");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
    }

    @Test
    void migrationCreatesFrozenOrderAndOutboxShapes() {
        assertEquals(
                List.of(
                        "id", "user_id", "sale_id", "reservation_id", "status", "amount",
                        "currency", "idempotency_key", "confirmed_at", "cancelled_at",
                        "expired_at", "cancel_reason", "version", "created_at", "updated_at"
                ),
                columns("orders")
        );
        assertEquals(
                List.of(
                        "id", "order_id", "event_id", "event_type", "event_version",
                        "occurred_at", "aggregate_id", "aggregate_type", "payload",
                        "published", "published_at", "created_at"
                ),
                columns("order_outbox")
        );
        assertTrue(indexExists("idx_orders_user_id_idempotency_key"));
        assertTrue(indexExists("idx_orders_reservation_id"));
        assertTrue(indexExists("idx_order_outbox_order_id"));
        assertFalse(indexExists("idx_order_outbox_unpublished"));
    }

    @Test
    void savesOrderAndExactUnpublishedOutboxWithoutRegeneratingIdentifiers()
            throws JsonProcessingException {
        Order order = order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "key");

        repository.save(order);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT id, order_id, event_id, event_type, event_version, occurred_at,
                       aggregate_id, aggregate_type, payload::text AS payload,
                       published, published_at, created_at
                FROM order_outbox
                """
        );
        assertEquals(order.outboxEvent().id().value(), row.get("id"));
        assertEquals(order.id().value(), row.get("order_id"));
        assertEquals(order.outboxEvent().event().eventId(), row.get("event_id"));
        assertEquals("OrderCreated", row.get("event_type"));
        assertEquals("1.0", row.get("event_version"));
        assertEquals(order.id().value(), row.get("aggregate_id"));
        assertEquals("Order", row.get("aggregate_type"));
        assertEquals(
                objectMapper.readTree("""
                        {"orderId":"%s","reservationId":"%s","userId":"%s",
                         "saleId":"%s","amount":"19.90","currency":"USD"}
                        """.formatted(
                                order.id(), order.purchaseIntentId(), order.userId(), order.saleId()
                        )),
                objectMapper.readTree(row.get("payload").toString())
        );
        assertEquals(false, row.get("published"));
        assertNull(row.get("published_at"));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_outbox"));
        assertEquals(0, count("idempotency_keys"));
    }

    @Test
    void outboxFailureRollsBackTheOrderWrite() {
        Order candidate = order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "key");
        UUID anchorOrderId = UUID.randomUUID();
        insertOrder(anchorOrderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "anchor");
        jdbcTemplate.update(
                """
                INSERT INTO order_outbox (
                    id, order_id, event_id, event_type, event_version, occurred_at,
                    aggregate_id, aggregate_type, payload, published, created_at
                ) VALUES (?, ?, ?, 'OrderCreated', '1.0', ?, ?, 'Order', '{}'::jsonb, FALSE, ?)
                """,
                UUID.randomUUID(), anchorOrderId, candidate.outboxEvent().event().eventId(),
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE, CREATED_AT.atOffset(ZoneOffset.UTC)),
                anchorOrderId,
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE, CREATED_AT.atOffset(ZoneOffset.UTC))
        );

        assertThrows(DataIntegrityViolationException.class, () -> repository.save(candidate));

        assertEquals(0, countById("orders", candidate.id().value()));
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_outbox"));
    }

    @Test
    void uniquenessIsUserScopedAndReservationScoped() {
        UUID firstUser = UUID.randomUUID();
        repository.save(order(firstUser, UUID.randomUUID(), UUID.randomUUID(), "same-key"));
        repository.save(order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "same-key"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> repository.save(order(firstUser, UUID.randomUUID(), UUID.randomUUID(), "same-key"))
        );

        UUID reservationId = UUID.randomUUID();
        repository.save(order(UUID.randomUUID(), UUID.randomUUID(), reservationId, "one"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> repository.save(order(UUID.randomUUID(), UUID.randomUUID(), reservationId, "two"))
        );
    }

    private Order order(UUID userId, UUID saleId, UUID reservationId, String key) {
        return Order.place(
                PurchaseIntentId.of(reservationId),
                UserId.of(userId),
                SaleId.of(saleId),
                Money.of("19.90", "USD"),
                key,
                CREATED_AT
        );
    }

    private void insertOrder(
            UUID id, UUID userId, UUID saleId, UUID reservationId, String key
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    id, user_id, sale_id, reservation_id, status, amount, currency,
                    idempotency_key, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PENDING', 19.90, 'USD', ?, 0, ?, ?)
                """,
                id, userId, saleId, reservationId, key,
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE, CREATED_AT.atOffset(ZoneOffset.UTC)),
                new SqlParameterValue(
                        Types.TIMESTAMP_WITH_TIMEZONE, CREATED_AT.atOffset(ZoneOffset.UTC))
        );
    }

    private List<String> columns(String table) {
        return jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """,
                String.class,
                table
        );
    }

    private boolean indexExists(String index) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class,
                index
        );
        return value != null && value == 1;
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class
        );
        return value == null ? 0 : value;
    }

    private int countById(String table, UUID id) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?",
                Integer.class,
                id
        );
        return value == null ? 0 : value;
    }

    @Test
    void orderForeignKeyUsesRestrictiveDelete() {
        Order order = order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "key");
        repository.save(order);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM orders WHERE id = ?", order.id().value())
        );
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_outbox"));
    }

}
