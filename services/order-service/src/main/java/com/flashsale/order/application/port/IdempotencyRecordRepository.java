package com.flashsale.order.application.port;

import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.Optional;

/** Durable authority for user-scoped idempotency records. */
public interface IdempotencyRecordRepository {

    Optional<IdempotencyRecord> find(UserId userId, String idempotencyKey);

    IdempotencyRecord saveIfAbsent(IdempotencyRecord record);
}
