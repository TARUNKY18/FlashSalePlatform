package com.flashsale.order.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.application.port.IdempotencyCachePort;
import com.flashsale.order.application.port.IdempotencyCacheUnavailableException;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis 24-hour cache for user-scoped idempotency responses. */
@Component
@Profile("infrastructure")
public class RedisIdempotencyCacheAdapter implements IdempotencyCachePort {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RedisIdempotencyCacheAdapter.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyCacheAdapter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IdempotencyRecord> find(UserId userId, String idempotencyKey) {
        String key = redisKey(userId, idempotencyKey);
        try {
            String serialized = redisTemplate.opsForValue().get(key);
            if (serialized == null) {
                return Optional.empty();
            }
            CachedResponse response = objectMapper.readValue(serialized, CachedResponse.class);
            return Optional.of(new IdempotencyRecord(
                    userId,
                    idempotencyKey,
                    response.responsePayload(),
                    response.httpStatus()
            ));
        } catch (DataAccessException ex) {
            throw unavailable("read", key, ex);
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException ex) {
            LOGGER.warn("Ignoring malformed idempotency cache value for key {}", key, ex);
            return Optional.empty();
        }
    }

    @Override
    public void put(IdempotencyRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        String key = redisKey(record.userId(), record.idempotencyKey());
        try {
            String serialized = objectMapper.writeValueAsString(
                    new CachedResponse(record.responsePayload(), record.httpStatus()));
            redisTemplate.opsForValue().set(key, serialized, TTL);
        } catch (DataAccessException | JsonProcessingException ex) {
            throw unavailable("write", key, ex);
        }
    }

    private static String redisKey(UserId userId, String idempotencyKey) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return "idem:" + userId.value() + ":" + idempotencyKey;
    }

    private static IdempotencyCacheUnavailableException unavailable(
            String operation,
            String key,
            Exception cause
    ) {
        return new IdempotencyCacheUnavailableException(
                "Idempotency cache " + operation + " failed for key: " + key,
                cause
        );
    }

    private record CachedResponse(String responsePayload, int httpStatus) {
    }
}
