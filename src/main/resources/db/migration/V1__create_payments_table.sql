CREATE TABLE payments
(
    id                 UUID           NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key    VARCHAR(255)   NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    status             VARCHAR(20)    NOT NULL,
    provider_reference VARCHAR(255),
    failure_reason     VARCHAR(512),
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP      NOT NULL DEFAULT now(),

    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key)
);

-- Serves the reconciliation job's "find stuck PENDING rows older than X" query.
CREATE INDEX idx_payments_status_created_at ON payments (status, created_at);
