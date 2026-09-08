CREATE TABLE payments (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL UNIQUE,
    user_id         UUID NOT NULL,
    amount          NUMERIC(19,2) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    transaction_ref VARCHAR(100),
    decline_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    topic           VARCHAR(100) NOT NULL,
    payload         TEXT NOT NULL,
    correlation_id  UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    id              UUID PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    processed_at    TIMESTAMPTZ NOT NULL
);
