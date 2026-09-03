package com.flashsale.order.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyRecordPersistenceMapperTest {

    private static final UUID USER_UUID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final IdempotencyRecordPersistenceMapper mapper =
            new IdempotencyRecordPersistenceMapper();

    @Test
    void mapsDomainToJpa() {
        IdempotencyRecord record = new IdempotencyRecord(
                UserId.of(USER_UUID), "opaque-key", "{\"ok\":true}", 202);

        IdempotencyRecordJpaEntity entity = mapper.toJpaEntity(record);

        assertEquals(USER_UUID, entity.getUserId());
        assertEquals("opaque-key", entity.getIdempotencyKey());
        assertEquals("{\"ok\":true}", entity.getResponsePayload());
        assertEquals(202, entity.getHttpStatus());
    }

    @Test
    void mapsJpaToDomain() {
        IdempotencyRecordJpaEntity entity = new IdempotencyRecordJpaEntity(
                USER_UUID, "opaque-key", "{\"ok\":true}", 409);

        IdempotencyRecord record = mapper.toDomain(entity);

        assertEquals(UserId.of(USER_UUID), record.userId());
        assertEquals("opaque-key", record.idempotencyKey());
        assertEquals("{\"ok\":true}", record.responsePayload());
        assertEquals(409, record.httpStatus());
    }

    @Test
    void rejectsNullValues() {
        assertThrows(NullPointerException.class, () -> mapper.toJpaEntity(null));
        assertThrows(NullPointerException.class, () -> mapper.toDomain(null));
    }
}
