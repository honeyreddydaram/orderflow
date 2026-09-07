CREATE TABLE products (
    id              UUID PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(2000),
    price           NUMERIC(10, 2) NOT NULL,
    stock_quantity  INTEGER NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_products_active ON products (active);
