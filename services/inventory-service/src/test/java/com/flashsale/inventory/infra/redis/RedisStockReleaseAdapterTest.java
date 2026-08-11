package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.StockReleaseResult;
import com.flashsale.inventory.application.port.StockReleaseUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RedisStockReleaseAdapterTest {

    private StockReleaseLuaExecutor luaExecutor;
    private RedisStockReleaseAdapter adapter;
    private final SaleId saleId = SaleId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        luaExecutor = mock(StockReleaseLuaExecutor.class);
        adapter     = new RedisStockReleaseAdapter(luaExecutor);
    }

    @Test
    void nonNegativeResultMapsToReleased() {
        when(luaExecutor.execute(saleId, 5, 100)).thenReturn(50L);

        assertEquals(StockReleaseResult.RELEASED, adapter.release(saleId, 5, 100));
    }

    @Test
    void zeroResultMapsToReleased() {
        when(luaExecutor.execute(saleId, 5, 5)).thenReturn(0L);

        assertEquals(StockReleaseResult.RELEASED, adapter.release(saleId, 5, 5));
    }

    @Test
    void minusTwoMapToSaleEnded() {
        when(luaExecutor.execute(saleId, 2, 100)).thenReturn(-2L);

        assertEquals(StockReleaseResult.SALE_ENDED, adapter.release(saleId, 2, 100));
    }

    @Test
    void nullResultThrowsUnavailable() {
        when(luaExecutor.execute(saleId, 1, 100)).thenReturn(null);

        assertThrows(StockReleaseUnavailableException.class,
                () -> adapter.release(saleId, 1, 100));
    }

    @Test
    void transportExceptionThrowsUnavailable() {
        when(luaExecutor.execute(saleId, 1, 100))
                .thenThrow(new DataAccessResourceFailureException("timeout"));

        assertThrows(StockReleaseUnavailableException.class,
                () -> adapter.release(saleId, 1, 100));
    }

    @Test
    void unknownNegativeResultThrowsUnavailable() {
        when(luaExecutor.execute(saleId, 1, 100)).thenReturn(-99L);

        assertThrows(StockReleaseUnavailableException.class,
                () -> adapter.release(saleId, 1, 100));
    }
}
