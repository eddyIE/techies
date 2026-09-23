CREATE TABLE stock_items (
    product_id UUID PRIMARY KEY,
    available  INT         NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,

    -- Backstop: even a logic bug cannot drive stock negative. The conditional UPDATE in
    -- StockService is the primary guard; this is the one that cannot be bypassed.
    CONSTRAINT ck_stock_available_non_negative CHECK (available >= 0)
);

CREATE TABLE stock_movements (
    id         UUID PRIMARY KEY,
    order_ref  VARCHAR(20) NOT NULL,
    type       VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_stock_movement_type CHECK (type IN ('DEDUCT', 'RESTORE')),
    -- The idempotency key: one DEDUCT and one RESTORE per order, ever. A retried call
    -- collides here instead of moving stock twice.
    CONSTRAINT ux_stock_movement_order_type UNIQUE (order_ref, type)
);

CREATE INDEX ix_stock_movements_order_ref ON stock_movements (order_ref);

CREATE TABLE stock_movement_items (
    id          UUID PRIMARY KEY,
    movement_id UUID NOT NULL REFERENCES stock_movements (id) ON DELETE CASCADE,
    product_id  UUID NOT NULL,
    quantity    INT  NOT NULL CHECK (quantity > 0)
);

CREATE INDEX ix_stock_movement_items_movement ON stock_movement_items (movement_id);
