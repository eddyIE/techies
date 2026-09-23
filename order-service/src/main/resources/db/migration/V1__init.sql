CREATE TABLE carts (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE cart_items (
    id         UUID PRIMARY KEY,
    cart_id    UUID        NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    product_id UUID        NOT NULL,
    quantity   INT         NOT NULL CHECK (quantity >= 1 AND quantity <= 99),
    added_at   TIMESTAMPTZ NOT NULL,

    -- Re-adding a product increments its line instead of creating a second one.
    CONSTRAINT ux_cart_items_cart_product UNIQUE (cart_id, product_id)
);

CREATE INDEX ix_cart_items_cart ON cart_items (cart_id);
