package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flashsale.inventory.application.DurableStockDecrementResult;
import com.flashsale.inventory.application.port.DurableStockDecrementPort;
import com.flashsale.inventory.application.port.DurableStockUnavailableException;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DurableStockDecrementIntegrationTest
        extends InventoryInfrastructureTestSupport {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("730e76a2-c2a1-4545-8d70-d952d70a8e50");
    private static final UUID STOCK_LEVEL_UUID =
            UUID.fromString("d815ea05-2734-43a4-9f7f-4a333c4de449");
    private static final UUID SALE_UUID =
            UUID.fromString("17ec4879-654d-4c80-98ca-2c33ccf55b47");
    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_UUID);
    private static final SaleId SALE_ID = SaleId.of(SALE_UUID);

    @Autowired
    private DurableStockDecrementPort durableStockDecrementPort;

    @Test
    void flywayMigrationAndHibernateValidationStartAgainstRealPostgres() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success",
                Integer.class
        );
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('products', 'stock_levels')
                """,
                Integer.class
        );

        assertTrue(migrationCount != null && migrationCount >= 1);
        assertEquals(2, tableCount);
    }

    @Test
    void successfulDecrementAdvancesOnlyStockLevelRevision() {
        insertProductWithStock(
                PRODUCT_UUID,
                STOCK_LEVEL_UUID,
                SALE_UUID,
                100,
                4L,
                10,
                10,
                6L
        );

        DurableStockDecrementResult result =
                durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 3);

        DurableStockDecrementResult.Decremented decremented = assertInstanceOf(
                DurableStockDecrementResult.Decremented.class,
                result
        );
        assertEquals(StockCount.of(7), decremented.remainingStock());
        assertEquals(7L, decremented.revision());
        assertEquals(new PersistedStock(7, 7L, 4L), persistedStock(PRODUCT_UUID, SALE_UUID));
    }

    @Test
    void insufficientStockDoesNotMutateOrAdvanceRevision() {
        insertProductWithStock(
                PRODUCT_UUID,
                STOCK_LEVEL_UUID,
                SALE_UUID,
                100,
                4L,
                10,
                2,
                6L
        );

        DurableStockDecrementResult result =
                durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 3);

        DurableStockDecrementResult.Insufficient insufficient = assertInstanceOf(
                DurableStockDecrementResult.Insufficient.class,
                result
        );
        assertEquals(StockCount.of(2), insufficient.currentStock());
        assertEquals(6L, insufficient.revision());
        assertEquals(new PersistedStock(2, 6L, 4L), persistedStock(PRODUCT_UUID, SALE_UUID));
    }

    @Test
    void pessimisticProductLockPreventsNegativeStockAndExcessSuccess() throws Exception {
        int availableStock = 12;
        int attempts = 24;
        insertProductWithStock(
                PRODUCT_UUID,
                STOCK_LEVEL_UUID,
                SALE_UUID,
                100,
                4L,
                availableStock,
                availableStock,
                0L
        );
        CountDownLatch start = new CountDownLatch(1);
        List<Future<DurableStockDecrementResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return durableStockDecrementPort.decrement(
                            PRODUCT_ID,
                            SALE_ID,
                            1
                    );
                }));
            }
            start.countDown();

            int successes = 0;
            int insufficient = 0;
            for (Future<DurableStockDecrementResult> future : futures) {
                DurableStockDecrementResult result = future.get(30, TimeUnit.SECONDS);
                if (result instanceof DurableStockDecrementResult.Decremented) {
                    successes++;
                } else {
                    insufficient++;
                }
            }

            assertEquals(availableStock, successes);
            assertEquals(attempts - availableStock, insufficient);
        }

        assertEquals(
                new PersistedStock(0, availableStock, 4L),
                persistedStock(PRODUCT_UUID, SALE_UUID)
        );
    }

    @Test
    void deferredCommitFailureIsTranslatedAndRollsBackDurableMutation() {
        insertProductWithStock(
                PRODUCT_UUID,
                STOCK_LEVEL_UUID,
                SALE_UUID,
                100,
                4L,
                10,
                10,
                6L
        );
        jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_stock_commit()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced deferred stock commit failure';
                END;
                $$
                """
        );
        jdbcTemplate.execute(
                """
                CREATE CONSTRAINT TRIGGER fail_stock_commit_trigger
                AFTER UPDATE ON stock_levels
                DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW
                EXECUTE FUNCTION fail_stock_commit()
                """
        );

        try {
            assertThrows(
                    DurableStockUnavailableException.class,
                    () -> durableStockDecrementPort.decrement(
                            PRODUCT_ID,
                            SALE_ID,
                            1
                    )
            );
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER fail_stock_commit_trigger ON stock_levels"
            );
            jdbcTemplate.execute("DROP FUNCTION fail_stock_commit()");
        }

        assertEquals(
                new PersistedStock(10, 6L, 4L),
                persistedStock(PRODUCT_UUID, SALE_UUID)
        );
    }

    @Test
    void missingProductKeepsNoSuchElementSemantics() {
        assertThrows(
                NoSuchElementException.class,
                () -> durableStockDecrementPort.decrement(PRODUCT_ID, SALE_ID, 1)
        );
    }
}
