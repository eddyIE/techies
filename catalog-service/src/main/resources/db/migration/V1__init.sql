-- Accent-insensitive search: "dien thoai" must match "Điện thoại".
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- unaccent() is STABLE, not IMMUTABLE, so it cannot be indexed directly. Pinning the
-- dictionary argument makes this wrapper safely immutable.
CREATE OR REPLACE FUNCTION immutable_unaccent(text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT unaccent('unaccent', $1) $$;

CREATE TABLE categories (
    id            UUID PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    slug          VARCHAR(140) NOT NULL UNIQUE,
    image_url     VARCHAR(500),
    display_order INT          NOT NULL DEFAULT 0
);

CREATE TABLE products (
    id            UUID PRIMARY KEY,
    category_id   UUID           NOT NULL REFERENCES categories (id),
    name          VARCHAR(200)   NOT NULL,
    slug          VARCHAR(220)   NOT NULL UNIQUE,
    description   TEXT           NOT NULL,
    price         NUMERIC(19, 2) NOT NULL CHECK (price > 0),
    thumbnail_url VARCHAR(500),
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ    NOT NULL
);

CREATE INDEX ix_products_category ON products (category_id);
CREATE INDEX ix_products_active ON products (active);

-- Trigram GIN over the unaccented, lowercased name+description: supports the LIKE-based
-- keyword search without a sequential scan as the catalog grows.
CREATE INDEX ix_products_search_trgm
    ON products USING GIN (immutable_unaccent(LOWER(name || ' ' || description)) gin_trgm_ops);

CREATE TABLE product_images (
    id            UUID PRIMARY KEY,
    product_id    UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    url           VARCHAR(500) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0
);

CREATE INDEX ix_product_images_product ON product_images (product_id);
