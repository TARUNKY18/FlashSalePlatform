package com.flashsale.order.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.application.port.IdempotencyCacheUnavailableException;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisIdempotencyCacheAdapterTest {

    private static final UserId USER_ID =
            UserId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    private static final String KEY = "opaque-key";
    private static final String REDIS_KEY = "idem:" + USER_ID.value() + ":" + KEY;

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> operations = mock(ValueOperations.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RedisIdempotencyCacheAdapter adapter =
            new RedisIdempotencyCacheAdapter(redisTemplate, new ObjectMapper());

    RedisIdempotencyCacheAdapterTest() {
        when(redisTemplate.opsForValue()).thenReturn(operations);
    }

    @Test
    void hitReturnsStoredResponseWithoutExtendingTtl() {
        when(operations.get(REDIS_KEY))
                .thenReturn("{\"responsePayload\":\"{\\\"ok\\\":true}\",\"httpStatus\":202}");

        IdempotencyRecord record = adapter.find(USER_ID, KEY).orElseThrow();

        assertEquals("{\"ok\":true}", record.responsePayload());
        assertEquals(202, record.httpStatus());
        verify(operations).get(REDIS_KEY);
        verify(operations, never()).set(eq(REDIS_KEY), anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void missReturnsEmpty() {
        when(operations.get(REDIS_KEY)).thenReturn(null);

        assertTrue(adapter.find(USER_ID, KEY).isEmpty());
    }

    @Test
    void putUsesUserScopedKeyAndFreshTwentyFourHourTtl() {
        IdempotencyRecord record =
                new IdempotencyRecord(USER_ID, KEY, "{\"ok\":true}", 202);

        adapter.put(record);

        verify(operations).set(
                eq(REDIS_KEY),
                eq("{\"responsePayload\":\"{\\\"ok\\\":true}\",\"httpStatus\":202}"),
                eq(Duration.ofHours(24))
        );
    }

    @Test
    void malformedOrInvalidValueIsCacheMiss() {
        when(operations.get(REDIS_KEY)).thenReturn("not-json", "{\"httpStatus\":999}");

        assertTrue(adapter.find(USER_ID, KEY).isEmpty());
        assertTrue(adapter.find(USER_ID, KEY).isEmpty());
    }

    @Test
    void redisReadFailureIsUnavailable() {
        when(operations.get(REDIS_KEY))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThrows(IdempotencyCacheUnavailableException.class,
                () -> adapter.find(USER_ID, KEY));
    }

    @Test
    void redisWriteFailureIsUnavailable() {
        IdempotencyRecord record =
                new IdempotencyRecord(USER_ID, KEY, "{\"ok\":true}", 202);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("connection refused"))
                .when(operations).set(eq(REDIS_KEY), anyString(), eq(Duration.ofHours(24)));

        assertThrows(IdempotencyCacheUnavailableException.class, () -> adapter.put(record));
    }
}
