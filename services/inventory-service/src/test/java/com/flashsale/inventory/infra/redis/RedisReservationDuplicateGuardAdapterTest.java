package com.flashsale.inventory.infra.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.port.ReservationDuplicateGuardPort;
import com.flashsale.inventory.application.port.ReservationDuplicateGuardUnavailableException;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisReservationDuplicateGuardAdapterTest {

    private static final UserId USER_ID = UserId.of(UUID.fromString("aaaa0000-0000-0000-0000-000000000001"));
    private static final SaleId SALE_ID = SaleId.of(UUID.fromString("bbbb0000-0000-0000-0000-000000000002"));
    private static final String EXPECTED_KEY =
            "resv:lock:" + USER_ID.value() + ":" + SALE_ID.value();

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redisTemplate    = mock(StringRedisTemplate.class);
    private final ReservationDuplicateGuardPort adapter =
            new RedisReservationDuplicateGuardAdapter(redisTemplate);

    {
        when(redisTemplate.opsForValue()).thenReturn(ops);
    }

    @Test
    void setIfAbsentTrueReturnsTrue() {
        when(ops.setIfAbsent(eq(EXPECTED_KEY), eq("1"), eq(Duration.ofSeconds(30))))
                .thenReturn(true);

        assertTrue(adapter.tryAcquire(USER_ID, SALE_ID));
    }

    @Test
    void setIfAbsentFalseReturnsFalse() {
        when(ops.setIfAbsent(eq(EXPECTED_KEY), eq("1"), eq(Duration.ofSeconds(30))))
                .thenReturn(false);

        assertFalse(adapter.tryAcquire(USER_ID, SALE_ID));
    }

    @Test
    void nullResultThrowsUnavailableException() {
        when(ops.setIfAbsent(any(), any(), any())).thenReturn(null);

        assertThrows(ReservationDuplicateGuardUnavailableException.class,
                () -> adapter.tryAcquire(USER_ID, SALE_ID));
    }

    @Test
    void connectionFailureThrowsUnavailableException() {
        when(ops.setIfAbsent(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("Connection refused"));

        assertThrows(ReservationDuplicateGuardUnavailableException.class,
                () -> adapter.tryAcquire(USER_ID, SALE_ID));
    }

    @Test
    void queryTimeoutThrowsUnavailableException() {
        when(ops.setIfAbsent(any(), any(), any()))
                .thenThrow(new QueryTimeoutException("Timeout"));

        assertThrows(ReservationDuplicateGuardUnavailableException.class,
                () -> adapter.tryAcquire(USER_ID, SALE_ID));
    }

    @Test
    void keyFormatUsesResvLockPrefix() {
        when(ops.setIfAbsent(any(), any(), any())).thenReturn(true);

        adapter.tryAcquire(USER_ID, SALE_ID);

        verify(ops).setIfAbsent(eq(EXPECTED_KEY), eq("1"), eq(Duration.ofSeconds(30)));
    }
}
