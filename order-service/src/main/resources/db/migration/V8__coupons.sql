-- Fixed-amount discount coupons.
--
-- Deliberately the simplest rule that is still useful: a flat amount off, optionally gated on
-- a minimum order value, with an active flag and an expiry. No percentage tiers, no per-customer
-- limits, no stacking -- one code per order.
--
-- The discount is snapshotted onto the order alongside the code, for the same reason prices and
-- names are: editing or withdrawing a coupon later must not change what a past order charged.

CREATE TABLE coupons (
    code            VARCHAR(32)    PRIMARY KEY,
    description     VARCHAR(200)   NOT NULL,
    discount_amount NUMERIC(19, 2) NOT NULL,
    -- NULL means the coupon applies to any order value.
    min_order_total NUMERIC(19, 2),
    active          BOOLEAN        NOT NULL DEFAULT TRUE,
    -- NULL means it never expires.
    expires_at      TIMESTAMPTZ,

    CONSTRAINT ck_coupons_discount_positive CHECK (discount_amount > 0),
    CONSTRAINT ck_coupons_min_total_positive CHECK (min_order_total IS NULL OR min_order_total > 0)
);

ALTER TABLE orders ADD COLUMN coupon_code VARCHAR(32);
ALTER TABLE orders ADD COLUMN discount NUMERIC(19, 2) NOT NULL DEFAULT 0;

-- The discount can never exceed what is being discounted: a coupon must not make an order
-- negative, and capping in code alone would leave a logic bug free to write a negative total.
ALTER TABLE orders ADD CONSTRAINT ck_orders_discount_within_subtotal
    CHECK (discount >= 0 AND discount <= subtotal);

-- Sample coupons for the demo. See docs/API.md.
INSERT INTO coupons (code, description, discount_amount, min_order_total, active, expires_at) VALUES
  ('TECHIES50K',  'Giảm 50.000đ cho đơn từ 500.000đ',      50000.00,   500000.00, TRUE,  NULL),
  ('TECHIES500K', 'Giảm 500.000đ cho đơn từ 10.000.000đ',  500000.00, 10000000.00, TRUE, NULL),
  ('FREESHIP30K', 'Giảm 30.000đ, áp dụng cho mọi đơn',     30000.00,   NULL,      TRUE,  NULL),
  ('EXPIRED100K', 'Đã hết hạn -- dùng để demo nhánh từ chối', 100000.00, NULL,    TRUE,  NOW() - INTERVAL '7 days'),
  ('PAUSED200K',  'Đang tạm tắt -- dùng để demo nhánh từ chối', 200000.00, NULL,  FALSE, NULL);
