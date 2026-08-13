package com.flashsale.inventory.integration;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.inventory.api.dto.CreateReservationRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ReservationConcurrencyIntegrationTest extends InventoryInfrastructureTestSupport {

    private static final int REQUEST_COUNT = 1_500;
    private static final int INITIAL_STOCK = 1_000;
    private static final UUID PRODUCT_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID STOCK_LEVEL_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID SALE_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final String STOCK_KEY = "stock:{" + SALE_ID + "}";
    private static final String VERSION_KEY = "stock:version:{" + SALE_ID + "}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedSaleStock() {
        insertProductWithStock(
                PRODUCT_ID,
                STOCK_LEVEL_ID,
                SALE_ID,
                INITIAL_STOCK,
                0L,
                INITIAL_STOCK,
                INITIAL_STOCK,
                0L
        );
        redisTemplate.opsForValue().set(STOCK_KEY, Integer.toString(INITIAL_STOCK));
        redisTemplate.opsForValue().set(VERSION_KEY, "0");
    }

    @Test
    @Timeout(value = 6, unit = MINUTES)
    void fifteenHundredConcurrentRequestsReserveExactlyOneThousandUnits() throws Exception {
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpOutcome>> futures = new ArrayList<>(REQUEST_COUNT);
        List<HttpOutcome> outcomes = new ArrayList<>(REQUEST_COUNT);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int requestNumber = 1; requestNumber <= REQUEST_COUNT; requestNumber++) {
                int identity = requestNumber;
                futures.add(executor.submit(() -> performRequest(identity, ready, start)));
            }

            awaitReady(ready);
            long completionDeadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            start.countDown();

            for (Future<HttpOutcome> future : futures) {
                outcomes.add(awaitOutcome(future, completionDeadline));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }

        Map<Integer, Long> statusCounts = outcomes.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        HttpOutcome::status,
                        java.util.stream.Collectors.counting()
                ));
        long soldOut = outcomes.stream()
                .filter(outcome -> outcome.status() == 409 && "SOLD_OUT".equals(outcome.error()))
                .count();
        long duplicate = outcomes.stream()
                .filter(outcome -> "DUPLICATE_RESERVATION".equals(outcome.error()))
                .count();
        long unexpected = outcomes.stream()
                .filter(outcome -> outcome.status() != 201
                        && !(outcome.status() == 409 && "SOLD_OUT".equals(outcome.error())))
                .count();

        PersistedStock stock = persistedStock(PRODUCT_ID, SALE_ID);
        long reservationCount = scalar("SELECT COUNT(*) FROM reservations");
        long pendingCount = scalar("SELECT COUNT(*) FROM reservations WHERE status = 'PENDING'");
        long terminalCount = scalar(
                "SELECT COUNT(*) FROM reservations WHERE status IN ('CONFIRMED', 'EXPIRED', 'RELEASED')"
        );
        long reservedQuantity = scalar("SELECT COALESCE(SUM(quantity), 0) FROM reservations");
        long distinctReservationIds = scalar("SELECT COUNT(DISTINCT id) FROM reservations");
        long distinctUsers = scalar("SELECT COUNT(DISTINCT user_id) FROM reservations");
        long distinctIdempotencyKeys = scalar(
                "SELECT COUNT(DISTINCT idempotency_key) FROM reservations"
        );
        long wrongReservationTarget = scalar(
                "SELECT COUNT(*) FROM reservations WHERE product_id <> ? OR sale_id <> ?",
                PRODUCT_ID,
                SALE_ID
        );
        long wrongReservationQuantity = scalar(
                "SELECT COUNT(*) FROM reservations WHERE quantity <> 1"
        );

        long outboxCount = scalar("SELECT COUNT(*) FROM inventory_outbox");
        long stockReservedOutboxCount = scalar(
                "SELECT COUNT(*) FROM inventory_outbox WHERE event_type = 'StockReserved'"
        );
        long distinctEventIds = scalar(
                "SELECT COUNT(DISTINCT event_id) FROM inventory_outbox"
        );
        long distinctOutboxReservationIds = scalar(
                "SELECT COUNT(DISTINCT reservation_id) FROM inventory_outbox"
        );
        long unlinkedOutboxRows = scalar(
                """
                SELECT COUNT(*)
                FROM inventory_outbox outbox
                LEFT JOIN reservations reservation ON reservation.id = outbox.reservation_id
                WHERE reservation.id IS NULL
                """
        );

        assertAll(
                () -> assertEquals(REQUEST_COUNT, outcomes.size()),
                () -> assertEquals(1_000L, statusCounts.getOrDefault(201, 0L)),
                () -> assertEquals(500L, statusCounts.getOrDefault(409, 0L)),
                () -> assertEquals(2, statusCounts.size(), "unexpected HTTP status present"),
                () -> assertEquals(500L, soldOut),
                () -> assertEquals(0L, duplicate),
                () -> assertEquals(0L, unexpected),
                () -> assertEquals(0, stock.currentStock()),
                () -> assertEquals(1_000L, stock.stockRevision()),
                () -> assertEquals(0L, stock.productVersion()),
                () -> assertEquals(1_000L, reservationCount),
                () -> assertEquals(1_000L, pendingCount),
                () -> assertEquals(0L, terminalCount),
                () -> assertEquals(1_000L, reservedQuantity),
                () -> assertEquals(1_000L, distinctReservationIds),
                () -> assertEquals(1_000L, distinctUsers),
                () -> assertEquals(1_000L, distinctIdempotencyKeys),
                () -> assertEquals(0L, wrongReservationTarget),
                () -> assertEquals(0L, wrongReservationQuantity),
                () -> assertEquals(1_000L, outboxCount),
                () -> assertEquals(1_000L, stockReservedOutboxCount),
                () -> assertEquals(1_000L, distinctEventIds),
                () -> assertEquals(1_000L, distinctOutboxReservationIds),
                () -> assertEquals(0L, unlinkedOutboxRows),
                () -> assertEquals("0", redisTemplate.opsForValue().get(STOCK_KEY)),
                () -> assertEquals("1000", redisTemplate.opsForValue().get(VERSION_KEY))
        );
    }

    private HttpOutcome performRequest(
            int identity,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        UUID userId = new UUID(1L, identity);
        String idempotencyKey = new UUID(2L, identity).toString();
        CreateReservationRequest request = new CreateReservationRequest(
                userId.toString(),
                SALE_ID.toString(),
                PRODUCT_ID.toString(),
                1
        );

        ready.countDown();
        start.await();
        MvcResult result = mockMvc.perform(post("/api/v1/reservations")
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andReturn();
        int status = result.getResponse().getStatus();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        String error = body.has("error") ? body.get("error").asText() : null;
        return new HttpOutcome(status, error);
    }

    private static void awaitReady(CountDownLatch ready) throws InterruptedException {
        assertTrue(ready.await(30, java.util.concurrent.TimeUnit.SECONDS),
                "not all 1500 request tasks became ready within 30 seconds");
    }

    private static HttpOutcome awaitOutcome(
            Future<HttpOutcome> future,
            long completionDeadline
    ) throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = completionDeadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("1500 requests did not finish within the shared deadline");
        }
        return future.get(remaining, NANOSECONDS);
    }

    private long scalar(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private record HttpOutcome(int status, String error) {
    }
}
