CREATE TABLE stock_items (
    product_id      UUID PRIMARY KEY,
    available_qty   INTEGER NOT NULL,
    reserved_qty    INTEGER NOT NULL,
    version         BIGINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_stock_items_non_negative CHECK (available_qty >= 0 AND reserved_qty >= 0)
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL UNIQUE,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE reservation_items (
    id              UUID PRIMARY KEY,
    reservation_id  UUID NOT NULL REFERENCES reservations (id) ON DELETE CASCADE,
    product_id      UUID NOT NULL,
    quantity        INTEGER NOT NULL
);

CREATE INDEX idx_reservation_items_reservation_id ON reservation_items (reservation_id);

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
