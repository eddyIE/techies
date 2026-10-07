-- Points are a ledger, not a counter. Both balances are derived from it (SPEC-loyalty.md,
-- Data Model), so there is no second source of truth that can drift from the rows explaining it.
CREATE TABLE points_ledger (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL,
    entry_type VARCHAR(16) NOT NULL,
    points     INT         NOT NULL,
    reference  VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_points_entry_type CHECK (entry_type IN ('ORDER_EARN', 'GIFT_SPEND')),
    -- An earn can only add and a spend can only subtract, so SUM over the sign is enough to
    -- tell a balance from a lifetime total.
    CONSTRAINT ck_points_sign CHECK (
        (entry_type = 'ORDER_EARN' AND points > 0) OR
        (entry_type = 'GIFT_SPEND' AND points < 0)),
    -- The idempotency key, exactly as stock_movements uses (order_ref, type). One earn per
    -- order ever, one spend per redemption ever: a retried call collides here instead of
    -- crediting twice.
    CONSTRAINT ux_points_ledger_type_reference UNIQUE (entry_type, reference)
);

CREATE INDEX ix_points_ledger_user ON points_ledger (user_id, created_at DESC);

-- The ladder lives in a table so GET /loyalty/me can hand the app its thresholds and the app
-- never hardcodes them. Tier 0 is implicit: below tier 1's threshold.
CREATE TABLE tiers (
    tier                     SMALLINT PRIMARY KEY,
    threshold_points         INT      NOT NULL,
    voucher_discount_percent SMALLINT NOT NULL,

    CONSTRAINT ck_tiers_tier CHECK (tier BETWEEN 1 AND 3),
    CONSTRAINT ck_tiers_threshold CHECK (threshold_points > 0),
    CONSTRAINT ck_tiers_percent CHECK (voucher_discount_percent BETWEEN 1 AND 100)
);

CREATE TABLE gifts (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(500) NOT NULL,
    image_url   VARCHAR(500) NOT NULL,
    points_cost INT          NOT NULL,
    min_tier    SMALLINT     NOT NULL,
    stock       INT          NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT ck_gifts_points_cost CHECK (points_cost > 0),
    CONSTRAINT ck_gifts_min_tier CHECK (min_tier BETWEEN 0 AND 3),
    -- Backstop for the conditional UPDATE in the claim transaction, the same pairing
    -- stock_items uses in inventory.
    CONSTRAINT ck_gifts_stock_non_negative CHECK (stock >= 0)
);

CREATE TABLE gift_redemptions (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL,
    gift_id      UUID         NOT NULL REFERENCES gifts (id),
    code         VARCHAR(20)  NOT NULL,
    gift_name    VARCHAR(200) NOT NULL,
    points_spent INT          NOT NULL,
    claimed_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ux_gift_redemptions_code UNIQUE (code),
    -- This single constraint is the whole re-claim rule. A duplicated request collides here
    -- rather than needing an idempotency key of its own.
    CONSTRAINT ux_gift_redemptions_user_gift UNIQUE (user_id, gift_id),
    CONSTRAINT ck_gift_redemptions_points_spent CHECK (points_spent > 0)
);

CREATE INDEX ix_gift_redemptions_user ON gift_redemptions (user_id, claimed_at DESC);

-- orders.coupons is fixed-amount, global and infinitely reusable, so it cannot express a
-- per-customer single-use percentage. These are a separate thing with a separate lifecycle.
CREATE TABLE tier_vouchers (
    code               VARCHAR(32) PRIMARY KEY,
    user_id            UUID        NOT NULL,
    tier               SMALLINT    NOT NULL,
    discount_percent   SMALLINT    NOT NULL,
    issued_at          TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ,
    consumed_order_ref VARCHAR(20),
    consumed_at        TIMESTAMPTZ,

    CONSTRAINT ck_tier_vouchers_tier CHECK (tier BETWEEN 1 AND 3),
    CONSTRAINT ck_tier_vouchers_percent CHECK (discount_percent BETWEEN 1 AND 100),
    -- One voucher per tier per customer, ever, which is what makes issuing idempotent: a
    -- replayed award collides instead of minting a second.
    CONSTRAINT ux_tier_vouchers_user_tier UNIQUE (user_id, tier)
);

CREATE INDEX ix_tier_vouchers_user ON tier_vouchers (user_id, issued_at DESC);

INSERT INTO tiers (tier, threshold_points, voucher_discount_percent) VALUES
  (1, 10000, 10),
  (2, 30000, 30),
  (3, 60000, 50);

-- The catalogue spans the ladder so every branch of claim is demoable without editing data:
-- two gifts at tier 0, one per tier above it, one out of stock, and one nobody can afford.
INSERT INTO gifts (id, name, description, image_url, points_cost, min_tier, stock, active) VALUES
  ('ce2eb2ca-a432-5d29-9755-1f4f1abf2a14', 'Ốp lưng silicon',
   'Ốp lưng silicon chống sốc, nhận tại cửa hàng ElecGo gần nhất.',
   'https://picsum.photos/seed/op-lung-silicon/400', 500, 0, 50, TRUE),
  ('fa31e0b6-78d5-5739-a025-965512251dd0', 'Cáp sạc USB-C 1m',
   'Cáp sạc USB-C dài 1m, hỗ trợ sạc nhanh 20W.',
   'https://picsum.photos/seed/cap-usb-c/400', 800, 0, 40, TRUE),
  ('1d61e4c8-962b-55bd-b0c9-f6c1045571ab', 'Tai nghe có dây',
   'Tai nghe nhét tai có dây kèm micro, jack 3.5mm.',
   'https://picsum.photos/seed/tai-nghe-day/400', 2000, 1, 25, TRUE),
  ('f0fb47a7-58e4-576e-9470-3b91dc212271', 'Củ sạc nhanh 25W',
   'Củ sạc nhanh 25W chuẩn PD, tương thích iPhone và Android.',
   'https://picsum.photos/seed/sac-nhanh-25w/400', 5000, 2, 15, TRUE),
  ('5a895aa7-839d-5f07-9729-7f3a68806ac1', 'Tai nghe Bluetooth',
   'Tai nghe không dây chống ồn chủ động, hộp sạc 24 giờ.',
   'https://picsum.photos/seed/tai-nghe-bluetooth/400', 12000, 3, 10, TRUE),
  -- Out of stock on purpose: the GIFT_OUT_OF_STOCK branch needs a gift a tier 0 account can
  -- otherwise afford, or the balance check would answer first.
  ('e20b380a-f62c-56eb-9ba4-f7cbf404ee5c', 'Pin sạc dự phòng 10.000mAh',
   'Pin sạc dự phòng 10.000mAh, hai cổng ra. Tạm thời hết hàng.',
   'https://picsum.photos/seed/pin-du-phong/400', 1500, 0, 0, TRUE),
  -- Above tier 3's threshold, so INSUFFICIENT_POINTS stays reachable at every tier.
  ('378bdc0f-8cea-57b3-a646-86b07ac39296', 'Đồng hồ thông minh',
   'Đồng hồ thông minh đo nhịp tim và SpO2, pin 7 ngày.',
   'https://picsum.photos/seed/dong-ho-thong-minh/400', 999999, 0, 5, TRUE);
