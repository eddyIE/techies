-- Three products priced so a fresh account walks the whole tier ladder in three orders.
-- At 1 point per 1.000đ: 10.000 points reaches tier 1, +20.000 reaches tier 2, +30.000
-- reaches tier 3 (SPEC-loyalty.md, Seed Data). Ids are in docs/SEED-IDS.md.
--
-- Their own category, sorted last, so the app can hide them: a customer should never be
-- shown a product called "Demo". created_at is backdated for the same reason: the default
-- product list sorts NEWEST, and NOW() would put all three at the top of page one.
INSERT INTO categories (id, name, slug, image_url, display_order) VALUES
  ('4ef9d6be-fda4-514d-871f-3cb98fd5762b', 'Demo', 'demo',
   'https://picsum.photos/seed/demo/400', 99);

INSERT INTO products (id, category_id, name, slug, description, price, thumbnail_url, active, created_at) VALUES
  ('9c57e463-c2ec-560b-b0ca-c84f6a7a56e7', '4ef9d6be-fda4-514d-871f-3cb98fd5762b',
   'Demo A - 10 triệu', 'demo-tier-a',
   'Sản phẩm dùng để demo hệ thống điểm thưởng. Mua và hoàn tất đơn này để đạt hạng 1. Bảo hành 12 tháng.',
   10000000.00, 'https://picsum.photos/seed/demo-tier-a/600', TRUE, '2020-01-01 00:00:00+07'),
  ('0c00e340-1aee-5a44-90a5-1b734973daad', '4ef9d6be-fda4-514d-871f-3cb98fd5762b',
   'Demo B - 20 triệu', 'demo-tier-b',
   'Sản phẩm dùng để demo hệ thống điểm thưởng. Mua sau Demo A để đạt hạng 2. Bảo hành 12 tháng.',
   20000000.00, 'https://picsum.photos/seed/demo-tier-b/600', TRUE, '2020-01-01 00:00:00+07'),
  ('a55d0844-ed95-5ca5-b69b-374bcedf9e0e', '4ef9d6be-fda4-514d-871f-3cb98fd5762b',
   'Demo C - 30 triệu', 'demo-tier-c',
   'Sản phẩm dùng để demo hệ thống điểm thưởng. Mua sau Demo B để đạt hạng 3. Bảo hành 12 tháng.',
   30000000.00, 'https://picsum.photos/seed/demo-tier-c/600', TRUE, '2020-01-01 00:00:00+07');
