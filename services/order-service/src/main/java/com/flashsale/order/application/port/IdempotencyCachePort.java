package com.flashsale.order.application.port;

import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.UserId;
import java.util.Optional;

/** Redis cache boundary for user-scoped idempotency responses. */
public interface IdempotencyCachePort {

    Optional<IdempotencyRecord> find(UserId userId, String idempotencyKey);

    void put(IdempotencyRecord record);
}
