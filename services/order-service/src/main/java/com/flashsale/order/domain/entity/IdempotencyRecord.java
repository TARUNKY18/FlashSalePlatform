package com.flashsale.order.domain.entity;

import com.flashsale.order.domain.vo.UserId;
import java.util.Objects;

/** Permanent result of processing one user-scoped idempotency key. */
public record IdempotencyRecord(
        UserId userId,
        String idempotencyKey,
        String responsePayload,
        int httpStatus
) {

    public IdempotencyRecord {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(responsePayload, "responsePayload must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
    }
}
