package com.flashsale.order.application;

import com.flashsale.order.application.port.IdempotencyCachePort;
import com.flashsale.order.application.port.IdempotencyCacheUnavailableException;
import com.flashsale.order.application.port.IdempotencyRecordRepository;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Coordinates Redis-first lookup and PostgreSQL-authoritative persistence. */
@Service
@Profile("infrastructure")
public class IdempotencyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyCachePort cache;
    private final IdempotencyRecordRepository repository;

    public IdempotencyService(
            IdempotencyCachePort cache,
            IdempotencyRecordRepository repository
    ) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public Optional<IdempotencyRecord> find(UserId userId, String idempotencyKey) {
        requireIdentity(userId, idempotencyKey);
        try {
            Optional<IdempotencyRecord> cached = cache.find(userId, idempotencyKey);
            if (cached.isPresent()) {
                return cached;
            }
        } catch (IdempotencyCacheUnavailableException ex) {
            LOGGER.warn("Idempotency cache read failed; falling back to PostgreSQL", ex);
        }

        Optional<IdempotencyRecord> durable = repository.find(userId, idempotencyKey);
        durable.ifPresent(this::cacheBestEffort);
        return durable;
    }

    public IdempotencyRecord save(IdempotencyRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        IdempotencyRecord durable = repository.saveIfAbsent(record);
        cacheBestEffort(durable);
        return durable;
    }

    private void cacheBestEffort(IdempotencyRecord record) {
        try {
            cache.put(record);
        } catch (IdempotencyCacheUnavailableException ex) {
            LOGGER.warn("Idempotency cache write failed after PostgreSQL success", ex);
        }
    }

    private static void requireIdentity(UserId userId, String idempotencyKey) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
