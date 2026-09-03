package com.flashsale.order.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** JPA representation of a permanent user-scoped idempotency record. */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyRecordJpaEntity.Key.class)
public class IdempotencyRecordJpaEntity {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "response_payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "http_status", nullable = false, updatable = false)
    private int httpStatus;

    protected IdempotencyRecordJpaEntity() {
    }

    IdempotencyRecordJpaEntity(
            UUID userId,
            String idempotencyKey,
            String responsePayload,
            int httpStatus
    ) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.idempotencyKey = Objects.requireNonNull(
                idempotencyKey, "idempotencyKey must not be null");
        this.responsePayload = Objects.requireNonNull(
                responsePayload, "responsePayload must not be null");
        this.httpStatus = httpStatus;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** Composite JPA identity matching the database primary key. */
    public static final class Key implements Serializable {
        private UUID userId;
        private String idempotencyKey;

        public Key() {
        }

        public Key(UUID userId, String idempotencyKey) {
            this.userId = userId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(userId, that.userId)
                    && Objects.equals(idempotencyKey, that.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, idempotencyKey);
        }
    }
}
