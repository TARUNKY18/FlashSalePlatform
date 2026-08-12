package com.flashsale.inventory.integration;

import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.flashsale.inventory.infra.kafka.InventoryOutboxPublisher;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real PostgreSQL and Redis infrastructure for Inventory integration tests.
 */
@SpringBootTest
@Import(InventoryInfrastructureTestSupport.StandaloneRedisTestConfiguration.class)
public abstract class InventoryInfrastructureTestSupport {

    @MockBean
    private InventoryOutboxPublisher inventoryOutboxPublisher;

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16.3-alpine"))
                    .withDatabaseName("inventory_db")
                    .withUsername("flashsale")
                    .withPassword("flashsale_dev");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2.5-alpine"))
                    .withExposedPorts(6379);

    static {
        Startables.deepStart(Stream.of(POSTGRES, REDIS)).join();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void resetInfrastructure() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        jdbcTemplate.update("DELETE FROM inventory_outbox");
        jdbcTemplate.update("DELETE FROM stock_reservation_log");
        jdbcTemplate.update("DELETE FROM reservations");
        jdbcTemplate.update("DELETE FROM stock_levels");
        jdbcTemplate.update("DELETE FROM products");
    }

    protected void insertProductWithStock(
            UUID productId,
            UUID stockLevelId,
            UUID saleId,
            int totalStock,
            long productVersion,
            int totalAllocated,
            int currentStock,
            long stockRevision
    ) {
        jdbcTemplate.update(
                "INSERT INTO products (id, total_stock, version) VALUES (?, ?, ?)",
                productId,
                totalStock,
                productVersion
        );
        jdbcTemplate.update(
                """
                INSERT INTO stock_levels (
                    id,
                    product_id,
                    sale_id,
                    total_allocated,
                    current_stock,
                    version
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                stockLevelId,
                productId,
                saleId,
                totalAllocated,
                currentStock,
                stockRevision
        );
    }

    protected PersistedStock persistedStock(UUID productId, UUID saleId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    stock.current_stock,
                    stock.version,
                    product.version
                FROM stock_levels stock
                JOIN products product ON product.id = stock.product_id
                WHERE stock.product_id = ? AND stock.sale_id = ?
                """,
                (resultSet, rowNumber) -> new PersistedStock(
                        resultSet.getInt("current_stock"),
                        resultSet.getLong("version"),
                        resultSet.getLong(3)
                ),
                productId,
                saleId
        );
    }

    protected record PersistedStock(
            int currentStock,
            long stockRevision,
            long productVersion
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StandaloneRedisTestConfiguration {

        @Bean
        @Primary
        LettuceConnectionFactory inventoryTestRedisConnectionFactory() {
            return new LettuceConnectionFactory(
                    REDIS.getHost(),
                    REDIS.getMappedPort(6379)
            );
        }
    }
}
