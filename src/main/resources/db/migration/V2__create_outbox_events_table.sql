CREATE TABLE outbox_events
(
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    published_at TIMESTAMP,

    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
);

-- Serves the outbox publisher's "next PENDING batch, oldest first" query.
CREATE INDEX idx_outbox_events_status_created_at ON outbox_events (status, created_at);
