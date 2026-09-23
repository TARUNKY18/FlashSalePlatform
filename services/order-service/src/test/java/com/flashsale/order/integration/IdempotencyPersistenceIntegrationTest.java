package com.flashsale.order.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flashsale.order.application.port.IdempotencyRecordRepository;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class IdempotencyPersistenceIntegrationTest {

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

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearRecords() {
        jdbcTemplate.update("DELETE FROM idempotency_keys");
    }

    @Test
    void idempotencyTableRetainsPermanentShape() {
        assertTrue(tableExists("idempotency_keys"));
        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'idempotency_keys'
                ORDER BY ordinal_position
                """,
                String.class
        );
        assertEquals(List.of(
                "user_id", "idempotency_key", "response_payload", "http_status"), columns);
    }

    @Test
    void roundTripsOpaqueResponse() {
        IdempotencyRecord record = record(UUID.randomUUID(), "key", "first");

        repository.saveIfAbsent(record);

        assertEquals(record, repository.find(record.userId(), "key").orElseThrow());
    }

    @Test
    void sameKeyIsIndependentForDifferentUsers() {
        IdempotencyRecord first = record(UUID.randomUUID(), "same-key", "first");
        IdempotencyRecord second = record(UUID.randomUUID(), "same-key", "second");

        repository.saveIfAbsent(first);
        repository.saveIfAbsent(second);

        assertEquals(2, countByKey("same-key"));
    }

    @Test
    void duplicateReturnsPermanentExistingRecord() {
        UUID userId = UUID.randomUUID();
        IdempotencyRecord first = record(userId, "same-key", "first");
        IdempotencyRecord duplicate = record(userId, "same-key", "second");

        assertEquals(first, repository.saveIfAbsent(first));
        assertEquals(first, repository.saveIfAbsent(duplicate));
        assertEquals(1, countByKey("same-key"));
    }

    @Test
    void concurrentDuplicateReturnsOnePermanentRecord() throws Exception {
        UUID userId = UUID.randomUUID();
        IdempotencyRecord first = record(userId, "race-key", "first");
        IdempotencyRecord second = record(userId, "race-key", "second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<IdempotencyRecord> saveFirst = saveAfterBarrier(first, ready, start);
        Callable<IdempotencyRecord> saveSecond = saveAfterBarrier(second, ready, start);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(saveFirst);
            var secondResult = executor.submit(saveSecond);
            ready.await();
            start.countDown();

            assertEquals(firstResult.get(), secondResult.get());
        }
        assertEquals(1, countByKey("race-key"));
    }

    private Callable<IdempotencyRecord> saveAfterBarrier(
            IdempotencyRecord record,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            return repository.saveIfAbsent(record);
        };
    }

    private IdempotencyRecord record(UUID userId, String key, String payload) {
        return new IdempotencyRecord(UserId.of(userId), key, payload, 202);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """,
                Integer.class,
                tableName
        );
        return count != null && count == 1;
    }

    private int countByKey(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?",
                Integer.class,
                key
        );
        return count != null ? count : 0;
    }
}
