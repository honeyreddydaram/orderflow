CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL,
    user_id         UUID NOT NULL,
    type            VARCHAR(20) NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    message         TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_notifications_user_id ON notifications (user_id);

CREATE TABLE processed_events (
    id              UUID PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,
    processed_at    TIMESTAMPTZ NOT NULL
);
