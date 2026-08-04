package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.inventory.application.StockCounterService;
import com.flashsale.inventory.application.StockDecrementResult;
import com.flashsale.inventory.application.port.DurableStockDecrementPort;
import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.StockDecrementPort;
import com.flashsale.inventory.application.port.StockDecrementUnavailableException;
import com.flashsale.inventory.application.port.StockProjectionSyncPort;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.infra.redis.RedisStockDecrementAdapter;
import com.flashsale.inventory.infra.redis.StockDecrementLuaExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisPostgresFailoverIntegrationTest
        extends InventoryInfrastructureTestSupport {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("1163374c-4fdc-4246-82d8-9866eb94f739");
    private static final UUID STOCK_LEVEL_UUID =
            UUID.fromString("c8d1f17c-47a0-4620-970a-b31197de5359");
    private static final UUID SALE_UUID =
            UUID.fromString("dd43a2ac-7cd2-426a-93cc-dd8caedfe561");
    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_UUID);
    private static final SaleId SALE_ID = SaleId.of(SALE_UUID);
    private static final String STOCK_KEY = "stock:{" + SALE_ID + "}";
    private static final String VERSION_KEY = "stock:version:{" + SALE_ID + "}";

    @Autowired
    private StockCounterService stockCounterService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockDecrementPort stockDecrementPort;

    @Autowired
    private DurableStockDecrementPort durableStockDecrementPort;

    @Autowired
    private StockProjectionSyncPort stockProjectionSyncPort;

    @Autowired
    @Qualifier("stockDecrementScript")
    private RedisScript<Long> stockDecrementScript;

    @Test
    void redisLuaSuccessIsConfirmedByPostgresAndRevisionSynchronized() {
        seedDurableStock(10);
        setProjection(10, 0L);

        StockDecrementResult result =
                stockCounterService.decrement(PRODUCT_ID, SALE_ID, 1);

        StockDecrementResult.Decremented decremented =
                assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(StockCount.of(9), decremented.remainingStock());
        assertEquals(new PersistedStock(9, 1L, 4L), persistedStock(PRODUCT_UUID, SALE_UUID));
        assertProjection(9, 1L);
    }

    @Test
    void cacheMissUsesPostgresAndRedisRemainsMissing() {
        seedDurableStock(2);

        StockDecrementResult result =
                stockCounterService.decrement(PRODUCT_ID, SALE_ID, 1);

        StockDecrementResult.Decremented decremented =
                assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(StockCount.of(1), decremented.remainingStock());
        assertEquals(new PersistedStock(1, 1L, 4L), persistedStock(PRODUCT_UUID, SALE_UUID));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(STOCK_KEY)));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(VERSION_KEY)));
    }

    @Test
    void redisSoldOutButDurableSuccessReturnsDecrementedAndRepairsProjection() {
        seedDurableStock(10);
        setProjection(0, 0L);

        StockDecrementResult result =
                stockCounterService.decrement(PRODUCT_ID, SALE_ID, 1);

        assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(new PersistedStock(9, 1L, 4L), persistedStock(PRODUCT_UUID, SALE_UUID));
        assertProjection(9, 1L);
    }

    @Test
    void redisSuccessButDurableInsufficiencyReturnsSoldOutAndRepairsProjection() {
        seedDurableStock(0);
        setProjection(10, 0L);

        StockDecrementResult result =
                stockCounterService.decrement(PRODUCT_ID, SALE_ID, 1);

        assertInstanceOf(StockDecrementResult.SoldOut.class, result);
        assertEquals(new PersistedStock(0, 0L, 4L), persistedStock(PRODUCT_UUID, SALE_UUID));
        assertProjection(0, 0L);
    }

    @Test
    void unavailableRedisBeforeLuaStillInvokesOneDurableDecrement() {
        seedDurableStock(10);
        LettuceConnectionFactory unavailableConnection =
                new LettuceConnectionFactory("127.0.0.1", 1);
        unavailableConnection.afterPropertiesSet();
        unavailableConnection.start();
        try {
            StringRedisTemplate unavailableTemplate =
                    new StringRedisTemplate(unavailableConnection);
            StockDecrementPort unavailablePort = new RedisStockDecrementAdapter(
                    new StockDecrementLuaExecutor(
                            unavailableTemplate,
                            stockDecrementScript
                    )
            );
            StockCounterService service = new StockCounterService(
                    productRepository,
                    unavailablePort,
                    durableStockDecrementPort,
                    stockProjectionSyncPort
            );

            StockDecrementResult result =
                    service.decrement(PRODUCT_ID, SALE_ID, 1);

            assertInstanceOf(StockDecrementResult.Decremented.class, result);
            assertEquals(
                    new PersistedStock(9, 1L, 4L),
                    persistedStock(PRODUCT_UUID, SALE_UUID)
            );
        } finally {
            unavailableConnection.destroy();
        }
    }

    @Test
    void responseLossAfterLuaExecutionStillInvokesOneDurableDecrement() {
        seedDurableStock(10);
        setProjection(10, 0L);
        StockDecrementPort executeThenLoseResponse = (saleId, quantity) -> {
            stockDecrementPort.decrement(saleId, quantity);
            throw new StockDecrementUnavailableException(
                    "response lost after Lua execution",
                    new RuntimeException("simulated transport loss")
            );
        };
        StockCounterService service = new StockCounterService(
                productRepository,
                executeThenLoseResponse,
                durableStockDecrementPort,
                stockProjectionSyncPort
        );

        StockDecrementResult result =
                service.decrement(PRODUCT_ID, SALE_ID, 1);

        assertInstanceOf(StockDecrementResult.Decremented.class, result);
        assertEquals(new PersistedStock(9, 1L, 4L), persistedStock(PRODUCT_UUID, SALE_UUID));
        assertProjection(9, 1L);
    }

    @Test
    void deterministicRedisDataFailureFailsClosedWithoutDurableMutation() {
        seedDurableStock(10);
        redisTemplate.opsForList().rightPush(STOCK_KEY, "invalid-type");

        assertThrows(
                RedisSystemException.class,
                () -> stockCounterService.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        assertEquals(
                new PersistedStock(10, 0L, 4L),
                persistedStock(PRODUCT_UUID, SALE_UUID)
        );
    }

    @Test
    void concurrentCacheMissesSerializeInPostgresAndKeepRedisMissing() throws Exception {
        int availableStock = 12;
        int attempts = 24;
        seedDurableStock(availableStock);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StockDecrementResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return stockCounterService.decrement(PRODUCT_ID, SALE_ID, 1);
                }));
            }
            start.countDown();

            int successes = 0;
            int soldOut = 0;
            for (Future<StockDecrementResult> future : futures) {
                StockDecrementResult result = future.get(30, TimeUnit.SECONDS);
                if (result instanceof StockDecrementResult.Decremented) {
                    successes++;
                } else {
                    soldOut++;
                }
            }

            assertEquals(availableStock, successes);
            assertEquals(attempts - availableStock, soldOut);
        }

        assertEquals(
                new PersistedStock(0, availableStock, 4L),
                persistedStock(PRODUCT_UUID, SALE_UUID)
        );
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(STOCK_KEY)));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(VERSION_KEY)));
    }

    private void seedDurableStock(int currentStock) {
        insertProductWithStock(
                PRODUCT_UUID,
                STOCK_LEVEL_UUID,
                SALE_UUID,
                100,
                4L,
                100,
                currentStock,
                0L
        );
    }

    private void setProjection(int stock, long revision) {
        redisTemplate.opsForValue().set(STOCK_KEY, Integer.toString(stock));
        redisTemplate.opsForValue().set(VERSION_KEY, Long.toString(revision));
    }

    private void assertProjection(int stock, long revision) {
        assertEquals(Integer.toString(stock), redisTemplate.opsForValue().get(STOCK_KEY));
        assertEquals(
                Long.toString(revision),
                redisTemplate.opsForValue().get(VERSION_KEY)
        );
    }
}
