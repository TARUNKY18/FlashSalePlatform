package com.flashsale.order.infra.persistence;

import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Sole translation boundary between domain and JPA idempotency records. */
@Component
@Profile("infrastructure")
public class IdempotencyRecordPersistenceMapper {

    public IdempotencyRecordJpaEntity toJpaEntity(IdempotencyRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        return new IdempotencyRecordJpaEntity(
                record.userId().value(),
                record.idempotencyKey(),
                record.responsePayload(),
                record.httpStatus()
        );
    }

    public IdempotencyRecord toDomain(IdempotencyRecordJpaEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new IdempotencyRecord(
                UserId.of(entity.getUserId()),
                entity.getIdempotencyKey(),
                entity.getResponsePayload(),
                entity.getHttpStatus()
        );
    }
}
