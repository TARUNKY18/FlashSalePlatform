-- Task 5.3: permanent user-scoped idempotency records only.
CREATE TABLE idempotency_keys (
    user_id         UUID         NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    response_payload TEXT        NOT NULL,
    http_status     INTEGER      NOT NULL,

    CONSTRAINT idempotency_keys_pkey
        PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT idempotency_keys_http_status_ck
        CHECK (http_status BETWEEN 100 AND 599)
);
