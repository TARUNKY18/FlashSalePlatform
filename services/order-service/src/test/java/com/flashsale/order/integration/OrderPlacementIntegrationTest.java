package com.flashsale.order.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.application.port.IdempotencyCachePort;
import com.flashsale.order.application.port.IdempotencyCacheUnavailableException;
import com.flashsale.order.application.port.IdempotencyRecordRepository;
import com.flashsale.order.application.port.OrderRepository;
import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("infrastructure")
@Testcontainers(disabledWithoutDocker = true)
class OrderPlacementIntegrationTest {

    private static final UUID USER_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SALE_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID RESERVATION_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.3-alpine"))
                    .withDatabaseName("orders_db")
                    .withUsername("flashsale")
                    .withPassword("flashsale_dev");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @MockBean private IdempotencyCachePort cache;

    @BeforeEach
    void resetState() {
        jdbcTemplate.update("DELETE FROM order_outbox");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        reset(cache);
        when(cache.find(any(UserId.class), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void newRequestPersistsOneOrderOutboxAndExactIdempotencyResponse() throws Exception {
        String response = perform("new-key", request(USER_ID, SALE_ID, RESERVATION_ID))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, count("orders"));
        assertEquals(1, count("order_outbox"));
        assertEquals(1, count("idempotency_keys"));
        assertEquals(response, idempotencyRepository
                .find(UserId.of(USER_ID), "new-key").orElseThrow().responsePayload());
    }

    @Test
    void redisFailureFallsBackToPostgres() throws Exception {
        when(cache.find(any(UserId.class), eq("redis-down"))).thenThrow(
                new IdempotencyCacheUnavailableException(
                        "redis unavailable", new RuntimeException("connection refused")));

        perform("redis-down", request(USER_ID, SALE_ID, RESERVATION_ID))
                .andExpect(status().isAccepted());

        assertEquals(1, count("orders"));
        assertEquals(1, count("idempotency_keys"));
    }

    @Test
    void postgresIdempotencyHitReturnsStoredBodyWithoutCreatingOrder() throws Exception {
        String stored = "{ \"orderId\" : \"stored\", \"status\" : \"PENDING\" }";
        idempotencyRepository.saveIfAbsent(new IdempotencyRecord(
                UserId.of(USER_ID), "postgres-hit", stored, 202));

        perform("postgres-hit", request(USER_ID, SALE_ID, RESERVATION_ID))
                .andExpect(status().isAccepted())
                .andExpect(content().string(stored));

        assertEquals(0, count("orders"));
        assertEquals(0, count("order_outbox"));
    }

    @Test
    void crashGapRecoversOrderAndRepairsIdempotencyRecord() throws Exception {
        Order existing = order(USER_ID, SALE_ID, RESERVATION_ID, "crash-key");
        orderRepository.save(existing);

        String expected = canonicalResponse(existing.id().value());
        perform("crash-key", request(USER_ID, SALE_ID, RESERVATION_ID))
                .andExpect(status().isAccepted())
                .andExpect(content().string(expected));

        assertEquals(1, count("orders"));
        assertEquals(1, count("order_outbox"));
        assertEquals(expected, idempotencyRepository
                .find(UserId.of(USER_ID), "crash-key").orElseThrow().responsePayload());
    }

    @Test
    void concurrentSameKeyRequestsConvergeToOneOrderAndIdenticalResponse() throws Exception {
        String body = request(USER_ID, SALE_ID, RESERVATION_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> request = () -> {
            ready.countDown();
            start.await();
            var result = perform("race-key", body)
                    .andExpect(status().isAccepted())
                    .andReturn();
            return result.getResponse().getContentAsString();
        };

        String first;
        String second;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(request);
            var secondResult = executor.submit(request);
            ready.await();
            start.countDown();
            first = firstResult.get();
            second = secondResult.get();
        }

        assertEquals(first, second);
        JsonNode response = objectMapper.readTree(first);
        assertTrue(response.hasNonNull("orderId"));
        assertEquals("PENDING", response.get("status").asText());
        assertEquals(1, count("orders"));
        assertEquals(1, count("order_outbox"));
        assertEquals(1, count("idempotency_keys"));
    }

    @Test
    void differentKeyForOwnedReservationReturnsConflictWithoutSecondOrder() throws Exception {
        String body = request(USER_ID, SALE_ID, RESERVATION_ID);
        perform("first-key", body).andExpect(status().isAccepted());

        perform("second-key", body)
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"error":"DUPLICATE_RESERVATION",
                         "message":"An order already exists for this reservation."}
                        """));

        assertEquals(1, count("orders"));
        assertEquals(1, count("order_outbox"));
        assertEquals(1, count("idempotency_keys"));
    }

    private org.springframework.test.web.servlet.ResultActions perform(String key, String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String request(UUID userId, UUID saleId, UUID reservationId) {
        return """
                {"reservationId":"%s","userId":"%s","saleId":"%s",
                 "amount":100.00,"currency":"USD"}
                """.formatted(reservationId, userId, saleId);
    }

    private Order order(UUID userId, UUID saleId, UUID reservationId, String key) {
        return Order.place(
                PurchaseIntentId.of(reservationId),
                UserId.of(userId),
                SaleId.of(saleId),
                Money.of("100.00", "USD"),
                key,
                Instant.parse("2099-01-01T00:00:00Z")
        );
    }

    private String canonicalResponse(UUID orderId) {
        return "{\"orderId\":\"" + orderId + "\",\"status\":\"PENDING\"}";
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
