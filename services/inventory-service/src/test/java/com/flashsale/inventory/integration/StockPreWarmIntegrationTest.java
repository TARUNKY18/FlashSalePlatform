package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.flashsale.inventory.application.PreWarmStockResult;
import com.flashsale.inventory.application.PreWarmStockUseCase;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StockPreWarmIntegrationTest extends InventoryInfrastructureTestSupport {

    private static final UUID PRODUCT_ID_RAW =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID STOCK_LEVEL_ID_RAW =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SALE_ID_RAW =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_ID_RAW);
    private static final SaleId SALE_ID = SaleId.of(SALE_ID_RAW);

    private static final String STOCK_KEY = "stock:{" + SALE_ID_RAW + "}";
    private static final String VERSION_KEY = "stock:version:{" + SALE_ID_RAW + "}";

    @Autowired
    private PreWarmStockUseCase preWarmStockUseCase;

    // Sale starts in 30s (within execution window), ends in 1 hour
    private Instant saleStart() {
        return Instant.now().plusSeconds(30);
    }

    private Instant saleEnd(Instant saleStart) {
        return saleStart.plusSeconds(3600);
    }

    @Test
    void warmsStockWhenBothKeysMissing() {
        Instant start = saleStart();
        insertProductWithStock(PRODUCT_ID_RAW, STOCK_LEVEL_ID_RAW, SALE_ID_RAW,
                100, 0L, 100, 80, 3L);

        PreWarmStockResult result = preWarmStockUseCase.preWarm(
                PRODUCT_ID, SALE_ID, start, saleEnd(start)
        );

        assertEquals(PreWarmStockResult.WARMED, result);
        assertEquals("80", redisTemplate.opsForValue().get(STOCK_KEY));
        assertEquals("3", redisTemplate.opsForValue().get(VERSION_KEY));
        assertNotNull(redisTemplate.getExpire(STOCK_KEY));
        assertNotNull(redisTemplate.getExpire(VERSION_KEY));
    }

    @Test
    void returnsAlreadyCurrentForEqualRevision() {
        Instant start = saleStart();
        insertProductWithStock(PRODUCT_ID_RAW, STOCK_LEVEL_ID_RAW, SALE_ID_RAW,
                100, 0L, 100, 80, 3L);

        preWarmStockUseCase.preWarm(PRODUCT_ID, SALE_ID, start, saleEnd(start));

        PreWarmStockResult result = preWarmStockUseCase.preWarm(
                PRODUCT_ID, SALE_ID, start, saleEnd(start)
        );

        assertEquals(PreWarmStockResult.ALREADY_CURRENT, result);
        assertEquals("80", redisTemplate.opsForValue().get(STOCK_KEY));
    }

    @Test
    void returnsStaleIgnoredWhenDbRevisionIsOlderThanRedis() {
        Instant start = saleStart();

        // Seed DB with revision 5, warm Redis
        insertProductWithStock(PRODUCT_ID_RAW, STOCK_LEVEL_ID_RAW, SALE_ID_RAW,
                100, 0L, 100, 80, 5L);
        preWarmStockUseCase.preWarm(PRODUCT_ID, SALE_ID, start, saleEnd(start));

        // Rollback DB revision to 3 (simulates stale snapshot arriving late)
        jdbcTemplate.update(
                "UPDATE stock_levels SET version = 3 WHERE sale_id = ?",
                SALE_ID_RAW
        );

        PreWarmStockResult result = preWarmStockUseCase.preWarm(
                PRODUCT_ID, SALE_ID, start, saleEnd(start)
        );

        assertEquals(PreWarmStockResult.STALE_IGNORED, result);
        assertEquals("5", redisTemplate.opsForValue().get(VERSION_KEY));
    }

    @Test
    void updatesProjectionWhenNewerRevisionArrives() {
        Instant start = saleStart();

        insertProductWithStock(PRODUCT_ID_RAW, STOCK_LEVEL_ID_RAW, SALE_ID_RAW,
                100, 0L, 100, 80, 3L);
        preWarmStockUseCase.preWarm(PRODUCT_ID, SALE_ID, start, saleEnd(start));

        // Advance DB to revision 5 with updated stock
        jdbcTemplate.update(
                "UPDATE stock_levels SET version = 5, current_stock = 75 WHERE sale_id = ?",
                SALE_ID_RAW
        );

        PreWarmStockResult result = preWarmStockUseCase.preWarm(
                PRODUCT_ID, SALE_ID, start, saleEnd(start)
        );

        assertEquals(PreWarmStockResult.UPDATED, result);
        assertEquals("75", redisTemplate.opsForValue().get(STOCK_KEY));
        assertEquals("5", redisTemplate.opsForValue().get(VERSION_KEY));
    }

    @Test
    void repairePartialPairWhenOnlyVersionKeyPresent() {
        Instant start = saleStart();
        insertProductWithStock(PRODUCT_ID_RAW, STOCK_LEVEL_ID_RAW, SALE_ID_RAW,
                100, 0L, 100, 80, 3L);

        // Manually place only the version key (stock key absent)
        redisTemplate.opsForValue().set(VERSION_KEY, "3");
        redisTemplate.expire(VERSION_KEY, java.time.Duration.ofMinutes(70));

        PreWarmStockResult result = preWarmStockUseCase.preWarm(
                PRODUCT_ID, SALE_ID, start, saleEnd(start)
        );

        assertEquals(PreWarmStockResult.WARMED, result);
        assertEquals("80", redisTemplate.opsForValue().get(STOCK_KEY));
        assertEquals("3", redisTemplate.opsForValue().get(VERSION_KEY));
    }

    @Test
    void returnsInvalidStateForPartialPairWithOnlyStockKeyPresent() {
        Instant start = saleStart();
        insertProductWithStock(PRODUCT_ID_RAW, STOCK_LEVEL_ID_RAW, SALE_ID_RAW,
                100, 0L, 100, 80, 3L);

        // Manually place only the stock key (version key absent)
        redisTemplate.opsForValue().set(STOCK_KEY, "80");
        redisTemplate.expire(STOCK_KEY, java.time.Duration.ofMinutes(70));

        PreWarmStockResult result = preWarmStockUseCase.preWarm(
                PRODUCT_ID, SALE_ID, start, saleEnd(start)
        );

        assertEquals(PreWarmStockResult.INVALID_STATE, result);
        // Stock key is left untouched (fail closed)
        assertEquals("80", redisTemplate.opsForValue().get(STOCK_KEY));
        assertNull(redisTemplate.opsForValue().get(VERSION_KEY));
    }

    @Test
    void preservesExistingExpirationOnUpdate() {
        Instant start = saleStart();
        insertProductWithStock(PRODUCT_ID_RAW, STOCK_LEVEL_ID_RAW, SALE_ID_RAW,
                100, 0L, 100, 80, 3L);

        preWarmStockUseCase.preWarm(PRODUCT_ID, SALE_ID, start, saleEnd(start));
        Long originalExpiry = redisTemplate.getExpire(STOCK_KEY);

        // Advance DB to newer revision
        jdbcTemplate.update(
                "UPDATE stock_levels SET version = 5, current_stock = 75 WHERE sale_id = ?",
                SALE_ID_RAW
        );

        preWarmStockUseCase.preWarm(PRODUCT_ID, SALE_ID, start, saleEnd(start));
        Long updatedExpiry = redisTemplate.getExpire(STOCK_KEY);

        // Expiration is preserved (within 2-second tolerance for test execution time)
        assertNotNull(originalExpiry);
        assertNotNull(updatedExpiry);
        assertEquals(originalExpiry, updatedExpiry, 2L);
    }
}
