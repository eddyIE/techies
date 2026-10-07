-- A discount can now come from two places, and compensation has to know which: a loyalty
-- voucher must be released through loyalty-service, while a coupon needs nothing undone
-- because coupons are reusable (SPEC-loyalty.md, Vouchers are not coupons).
ALTER TABLE orders ADD COLUMN discount_source VARCHAR(16) NOT NULL DEFAULT 'NONE';

ALTER TABLE orders ADD CONSTRAINT ck_orders_discount_source
    CHECK (discount_source IN ('NONE', 'COUPON', 'LOYALTY_VOUCHER'));

-- Every existing code was a coupon: vouchers did not exist when these orders were placed.
UPDATE orders SET discount_source = 'COUPON' WHERE coupon_code IS NOT NULL;

-- A source other than NONE needs a code to go with it, and NONE must not carry one.
ALTER TABLE orders ADD CONSTRAINT ck_orders_discount_source_has_code
    CHECK ((discount_source = 'NONE') = (coupon_code IS NULL));
