package com.flashsale.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.order.application.port.IdempotencyCachePort;
import com.flashsale.order.application.port.IdempotencyCacheUnavailableException;
import com.flashsale.order.application.port.IdempotencyRecordRepository;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class IdempotencyServiceTest {

    private static final UserId USER_ID =
            UserId.of(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    private static final String KEY = "opaque-key";

    private final IdempotencyCachePort cache = mock(IdempotencyCachePort.class);
    private final IdempotencyRecordRepository repository =
            mock(IdempotencyRecordRepository.class);
    private final IdempotencyService service = new IdempotencyService(cache, repository);
    private final IdempotencyRecord record =
            new IdempotencyRecord(USER_ID, KEY, "{\"result\":true}", 202);

    @BeforeEach
    void cacheMissByDefault() {
        when(cache.find(USER_ID, KEY)).thenReturn(Optional.empty());
    }

    @Test
    void redisHitSkipsPostgres() {
        when(cache.find(USER_ID, KEY)).thenReturn(Optional.of(record));

        assertSame(record, service.find(USER_ID, KEY).orElseThrow());

        verifyNoInteractions(repository);
        verify(cache, never()).put(record);
    }

    @Test
    void redisMissFallsBackToPostgresAndRewarmsCache() {
        when(repository.find(USER_ID, KEY)).thenReturn(Optional.of(record));

        assertSame(record, service.find(USER_ID, KEY).orElseThrow());

        verify(repository).find(USER_ID, KEY);
        verify(cache).put(record);
    }

    @Test
    void redisReadFailureFallsBackToPostgres() {
        when(cache.find(USER_ID, KEY)).thenThrow(cacheFailure());
        when(repository.find(USER_ID, KEY)).thenReturn(Optional.of(record));

        assertSame(record, service.find(USER_ID, KEY).orElseThrow());
    }

    @Test
    void bothMissesReturnEmptyWithoutCacheWrite() {
        when(repository.find(USER_ID, KEY)).thenReturn(Optional.empty());

        assertTrue(service.find(USER_ID, KEY).isEmpty());

        verify(cache, never()).put(record);
    }

    @Test
    void saveCommitsPostgresBeforeBestEffortRedis() {
        IdempotencyRecord permanent =
                new IdempotencyRecord(USER_ID, KEY, "{\"existing\":true}", 202);
        when(repository.saveIfAbsent(record)).thenReturn(permanent);

        assertSame(permanent, service.save(record));

        InOrder order = inOrder(repository, cache);
        order.verify(repository).saveIfAbsent(record);
        order.verify(cache).put(permanent);
    }

    @Test
    void redisWriteFailureCannotHidePostgresSuccess() {
        when(repository.saveIfAbsent(record)).thenReturn(record);
        doThrow(cacheFailure()).when(cache).put(record);

        assertSame(record, service.save(record));
    }

    @Test
    void postgresLookupFailureFailsClosed() {
        RuntimeException failure = new RuntimeException("postgres unavailable");
        when(repository.find(USER_ID, KEY)).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.find(USER_ID, KEY)));
        verify(cache, never()).put(record);
    }

    @Test
    void postgresSaveFailureDoesNotWriteRedis() {
        RuntimeException failure = new RuntimeException("postgres unavailable");
        when(repository.saveIfAbsent(record)).thenThrow(failure);

        assertEquals(failure, assertThrows(RuntimeException.class, () -> service.save(record)));
        verify(cache, never()).put(record);
    }

    private static IdempotencyCacheUnavailableException cacheFailure() {
        return new IdempotencyCacheUnavailableException(
                "redis unavailable", new RuntimeException("connection refused"));
    }
}
