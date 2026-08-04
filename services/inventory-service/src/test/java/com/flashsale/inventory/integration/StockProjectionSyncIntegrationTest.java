package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.inventory.application.StockProjectionSyncResult;
import com.flashsale.inventory.application.port.StockProjectionSyncPort;
import com.flashsale.inventory.application.port.StockProjectionSyncUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class StockProjectionSyncIntegrationTest
        extends InventoryInfrastructureTestSupport {

    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("59258d22-2f62-4688-bc05-826b862756cb")
    );
    private static final String STOCK_KEY = "stock:{" + SALE_ID + "}";
    private static final String VERSION_KEY = "stock:version:{" + SALE_ID + "}";
    private static final DefaultRedisScript<Long> ABSOLUTE_EXPIRY_SCRIPT =
            new DefaultRedisScript<>(
                    "return redis.call('PEXPIRETIME', KEYS[1])",
                    Long.class
            );

    @Autowired
    private StockProjectionSyncPort stockProjectionSyncPort;

    @Test
    void newerRevisionAppliesAndEqualRevisionRepairsAuthoritativeStock() {
        setProjection("50", "5");

        StockProjectionSyncResult newer = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(41),
                6L
        );
        redisTemplate.opsForValue().set(STOCK_KEY, "99");
        StockProjectionSyncResult equal = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(41),
                6L
        );

        assertEquals(StockProjectionSyncResult.APPLIED, newer);
        assertEquals(StockProjectionSyncResult.APPLIED, equal);
        assertProjection("41", "6");
    }

    @Test
    void strictlyOlderRevisionIsIgnoredIncludingOutOfOrderSynchronization() {
        setProjection("50", "5");

        StockProjectionSyncResult newer = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(30),
                7L
        );
        StockProjectionSyncResult older = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(40),
                6L
        );

        assertEquals(StockProjectionSyncResult.APPLIED, newer);
        assertEquals(StockProjectionSyncResult.STALE_IGNORED, older);
        assertProjection("30", "7");
    }

    @Test
    void missingStockDeletesOrphanRevisionAndNeverRecreatesStock() {
        redisTemplate.opsForValue().set(VERSION_KEY, "5");

        StockProjectionSyncResult result = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(41),
                6L
        );

        assertEquals(StockProjectionSyncResult.MISSING, result);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(STOCK_KEY)));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(VERSION_KEY)));
    }

    @Test
    void independentRevisionLossAtomicallyInvalidatesUntrustedStock() {
        redisTemplate.opsForValue().set(STOCK_KEY, "50");

        StockProjectionSyncResult result = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(41),
                6L
        );

        assertEquals(StockProjectionSyncResult.MISSING, result);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(STOCK_KEY)));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(VERSION_KEY)));
    }

    @Test
    void persistentStockRemainsPersistentAndMakesRevisionPersistent() {
        setProjection("50", "5");
        redisTemplate.expire(VERSION_KEY, Duration.ofMinutes(1));

        StockProjectionSyncResult result = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(41),
                6L
        );

        assertEquals(StockProjectionSyncResult.APPLIED, result);
        assertEquals(-1L, redisTemplate.getExpire(STOCK_KEY, TimeUnit.MILLISECONDS));
        assertEquals(-1L, redisTemplate.getExpire(VERSION_KEY, TimeUnit.MILLISECONDS));
    }

    @Test
    void expiringStockKeepsExactExpiryAndRevisionMirrorsIt() {
        redisTemplate.opsForValue().set(
                STOCK_KEY,
                "50",
                Duration.ofMinutes(5)
        );
        redisTemplate.opsForValue().set(VERSION_KEY, "5");
        long originalStockExpiry = absoluteExpiry(STOCK_KEY);

        StockProjectionSyncResult result = stockProjectionSyncPort.synchronize(
                SALE_ID,
                StockCount.of(41),
                6L
        );

        assertEquals(StockProjectionSyncResult.APPLIED, result);
        assertEquals(originalStockExpiry, absoluteExpiry(STOCK_KEY));
        assertEquals(originalStockExpiry, absoluteExpiry(VERSION_KEY));
    }

    @Test
    void invalidStoredStockAndRevisionAreInfrastructureFailures() {
        setProjection("not-a-number", "5");
        assertThrows(
                StockProjectionSyncUnavailableException.class,
                () -> stockProjectionSyncPort.synchronize(
                        SALE_ID,
                        StockCount.of(41),
                        6L
                )
        );

        setProjection("50", "9223372036854775808");
        assertThrows(
                StockProjectionSyncUnavailableException.class,
                () -> stockProjectionSyncPort.synchronize(
                        SALE_ID,
                        StockCount.of(41),
                        6L
                )
        );
    }

    private void setProjection(String stock, String revision) {
        redisTemplate.opsForValue().set(STOCK_KEY, stock);
        redisTemplate.opsForValue().set(VERSION_KEY, revision);
    }

    private void assertProjection(String stock, String revision) {
        assertEquals(stock, redisTemplate.opsForValue().get(STOCK_KEY));
        assertEquals(revision, redisTemplate.opsForValue().get(VERSION_KEY));
    }

    private long absoluteExpiry(String key) {
        Long expiry = redisTemplate.execute(
                ABSOLUTE_EXPIRY_SCRIPT,
                List.of(key)
        );
        return expiry == null ? Long.MIN_VALUE : expiry;
    }
}
