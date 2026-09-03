package com.flashsale.order.infra.persistence;

import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for permanent user-scoped idempotency records. */
@Repository
@Profile("infrastructure")
public class IdempotencyRecordRepository
        implements com.flashsale.order.application.port.IdempotencyRecordRepository {

    private final SpringDataIdempotencyRecordRepository springDataRepository;
    private final IdempotencyRecordPersistenceMapper mapper;

    public IdempotencyRecordRepository(
            SpringDataIdempotencyRecordRepository springDataRepository,
            IdempotencyRecordPersistenceMapper mapper
    ) {
        this.springDataRepository = springDataRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(UserId userId, String idempotencyKey) {
        requireIdentity(userId, idempotencyKey);
        return springDataRepository
                .findByUserIdAndIdempotencyKey(userId.value(), idempotencyKey)
                .map(mapper::toDomain);
    }

    @Override
    @Transactional
    public IdempotencyRecord saveIfAbsent(IdempotencyRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        IdempotencyRecordJpaEntity entity = mapper.toJpaEntity(record);
        springDataRepository.insertIfAbsent(
                entity.getUserId(),
                entity.getIdempotencyKey(),
                entity.getResponsePayload(),
                entity.getHttpStatus()
        );
        return springDataRepository
                .findByUserIdAndIdempotencyKey(entity.getUserId(), entity.getIdempotencyKey())
                .map(mapper::toDomain)
                .orElseThrow(() -> new IllegalStateException(
                        "PostgreSQL did not return the persisted idempotency record"));
    }

    private static void requireIdentity(UserId userId, String idempotencyKey) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
