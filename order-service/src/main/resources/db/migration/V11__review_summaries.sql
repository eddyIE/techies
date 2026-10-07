-- The AI brief of a product's reviews, cached here rather than in ai-service: everything it
-- summarises is already local, and ai-service holds no schema by design (SPEC-ai.md).
CREATE TABLE product_review_summaries (
    -- Mirrors catalog.products.id. No FK: different schema, different service.
    product_id   UUID PRIMARY KEY,
    pros         JSONB        NOT NULL,
    cons         JSONB        NOT NULL,
    verdict      VARCHAR(500) NOT NULL,
    -- The cache key, not a timestamp. A summary is stale exactly when the review count no
    -- longer matches, which is precisely when its input changed. A TTL would either
    -- regenerate identical text on a schedule or serve one missing yesterday's reviews.
    review_count INT          NOT NULL,
    generated_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_review_summaries_count CHECK (review_count > 0)
);
