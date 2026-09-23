-- Per-day order numbering. The upsert is atomic, so two concurrent checkouts cannot be
-- issued the same reference.
CREATE TABLE order_ref_counters (
    day     DATE PRIMARY KEY,
    counter INT NOT NULL
);

CREATE TABLE orders (
    id             UUID PRIMARY KEY,
    order_ref      VARCHAR(20)    NOT NULL UNIQUE,
    user_id        UUID           NOT NULL,
    status         VARCHAR(16)    NOT NULL,
    failure_code   VARCHAR(32),

    subtotal       NUMERIC(19, 2) NOT NULL,
    shipping_fee   NUMERIC(19, 2) NOT NULL,
    total          NUMERIC(19, 2) NOT NULL,

    -- Shipping address SNAPSHOT. Never re-read from identity-service: a later edit to the
    -- user's address must not rewrite where a past order was sent.
    ship_recipient VARCHAR(120)   NOT NULL,
    ship_phone     VARCHAR(11)    NOT NULL,
    ship_line1     VARCHAR(255)   NOT NULL,
    ship_ward      VARCHAR(120)   NOT NULL,
    ship_district  VARCHAR(120)   NOT NULL,
    ship_province  VARCHAR(120)   NOT NULL,

    payment_method VARCHAR(16)    NOT NULL,
    payment_status VARCHAR(16)    NOT NULL,

    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_orders_payment_status CHECK (payment_status IN ('PENDING', 'PAID', 'DECLINED', 'REFUNDED'))
);

CREATE INDEX ix_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX ix_orders_status ON orders (status);

CREATE TABLE order_items (
    id           UUID PRIMARY KEY,
    order_id     UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id   UUID           NOT NULL,
    -- Name and price are SNAPSHOTS taken at checkout, not live catalog reads.
    product_name VARCHAR(200)   NOT NULL,
    unit_price   NUMERIC(19, 2) NOT NULL,
    quantity     INT            NOT NULL CHECK (quantity >= 1),
    line_total   NUMERIC(19, 2) NOT NULL
);

CREATE INDEX ix_order_items_order ON order_items (order_id);

-- Every saga step writes a row here. This is what makes the distributed transaction
-- demonstrable rather than merely described.
CREATE TABLE saga_steps (
    id         UUID PRIMARY KEY,
    order_id   UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    step_name  VARCHAR(48) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_saga_step_status CHECK (status IN ('STARTED', 'SUCCESS', 'FAILED', 'COMPENSATED'))
);

CREATE INDEX ix_saga_steps_order ON saga_steps (order_id, created_at);
