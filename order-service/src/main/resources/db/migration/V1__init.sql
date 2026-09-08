CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    status          VARCHAR(20) NOT NULL,
    total_amount    NUMERIC(10, 2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_orders_user_id ON orders (user_id);

CREATE TABLE order_items (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id      UUID NOT NULL,
    product_name    VARCHAR(200) NOT NULL,
    unit_price      NUMERIC(10, 2) NOT NULL,
    quantity        INTEGER NOT NULL,
    line_total      NUMERIC(10, 2) NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);

CREATE TABLE order_status_history (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    previous_status VARCHAR(20),
    new_status      VARCHAR(20) NOT NULL,
    reason          VARCHAR(500),
    changed_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_order_status_history_order_id ON order_status_history (order_id);

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
