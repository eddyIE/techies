-- Use real photographs of each product in place of the V3 text placeholders.
--
-- Each URL was resolved from the manufacturer or a retailer that sells the product, then
-- checked twice: the source page's own product name has to name the exact model, and the
-- URL has to return real image bytes. The second check matters because the catalogue is
-- 2023-24 hardware and the shops have moved on, so a loose match happily returns an
-- AirPods Pro 3 photo for an AirPods Pro 2. Products whose exact model could not be
-- confirmed keep their V3 placeholder rather than show the wrong generation.
--
-- These images are hotlinked and remain the copyright of their publishers, used here for a
-- non-published university demo. They are outside our control, so a URL can rotate or start
-- refusing hotlinks, and that image then 404s until this file is regenerated.

UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/42/305658/iphone-15-pro-max-blue-thumbnew-600x600.jpg' WHERE id = '1f8d1d18-6b82-542e-bcca-e7485fe03e0d'; -- iPhone 15 Pro Max 256GB
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/42/305658/iphone-15-pro-max-blue-thumbnew-600x600.jpg' WHERE product_id = '1f8d1d18-6b82-542e-bcca-e7485fe03e0d' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/42/281570/iphone-15-xanh-thumb-600x600.jpg' WHERE id = 'e0404394-59b0-5044-af93-6a93adc3af39'; -- iPhone 15 128GB
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/42/281570/iphone-15-xanh-thumb-600x600.jpg' WHERE product_id = 'e0404394-59b0-5044-af93-6a93adc3af39' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://mindthegapps.com/wp-content/uploads/2023/09/samsung-galaxy-s24-ultra-release-date-and-everything.webp' WHERE id = '34354145-85e1-5538-b6e5-a46656eebaf3'; -- Samsung Galaxy S24 Ultra 256GB
UPDATE product_images SET url = 'https://mindthegapps.com/wp-content/uploads/2023/09/samsung-galaxy-s24-ultra-release-date-and-everything.webp' WHERE product_id = '34354145-85e1-5538-b6e5-a46656eebaf3' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/42/322096/samsung-galaxy-a55-5g-blue-thumb-600x600.jpg' WHERE id = '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609'; -- Samsung Galaxy A55 5G
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/42/322096/samsung-galaxy-a55-5g-blue-thumb-600x600.jpg' WHERE product_id = '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/42/313889/xiaomi-14-ultra-black-thumbnew-600x600.jpg' WHERE id = '46be6c64-3e42-5c1d-b3d9-bfaa69128c81'; -- Xiaomi 14 Ultra
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/42/313889/xiaomi-14-ultra-black-thumbnew-600x600.jpg' WHERE product_id = '46be6c64-3e42-5c1d-b3d9-bfaa69128c81' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/42/321895/oppo-reno11-f-purple-thumb-600x600.jpg' WHERE id = 'd68f5695-3780-523e-a11e-151f7489d39d'; -- OPPO Reno11 F 5G
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/42/321895/oppo-reno11-f-purple-thumb-600x600.jpg' WHERE product_id = 'd68f5695-3780-523e-a11e-151f7489d39d' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://www.apple.com/newsroom/images/2023/10/apple-unveils-new-macbook-pro-featuring-m3-chips/article/Apple-MacBook-Pro-top-view-231030_big.jpg.large_2x.jpg' WHERE id = '287496fe-7be5-597e-926f-a3594e633cbf'; -- MacBook Pro M3 14 inch 16GB/512GB
UPDATE product_images SET url = 'https://www.apple.com/newsroom/images/2023/10/apple-unveils-new-macbook-pro-featuring-m3-chips/article/Apple-MacBook-Pro-top-view-231030_big.jpg.large_2x.jpg' WHERE product_id = '287496fe-7be5-597e-926f-a3594e633cbf' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/522/325513/ipad-pro-11-inch-m4-wifi-sliver-thumb-600x600.jpg' WHERE id = '9db2c05c-f0ef-5d7c-b075-377083159bf2'; -- iPad Pro M4 11 inch WiFi 256GB
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/522/325513/ipad-pro-11-inch-m4-wifi-sliver-thumb-600x600.jpg' WHERE product_id = '9db2c05c-f0ef-5d7c-b075-377083159bf2' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/522/325501/ipad-air-11-inch-m2-wifi-purple-thumb-600x600.jpg' WHERE id = 'f9f208fa-0a89-5e02-abcc-81521fd08651'; -- iPad Air M2 11 inch WiFi 128GB
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/522/325501/ipad-air-11-inch-m2-wifi-purple-thumb-600x600.jpg' WHERE product_id = 'f9f208fa-0a89-5e02-abcc-81521fd08651' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/522/294103/iPad-Gen-10-sliver-thumb-600x600.jpg' WHERE id = 'a6ce327b-7cc2-585c-9824-94daaf664d0c'; -- iPad Gen 10 WiFi 64GB
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/522/294103/iPad-Gen-10-sliver-thumb-600x600.jpg' WHERE product_id = 'a6ce327b-7cc2-585c-9824-94daaf664d0c' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/522/309818/galaxy-tab-s9-fe-grey-thumb-600x600.jpg' WHERE id = '0de535db-f821-566a-8093-6288472bb016'; -- Samsung Galaxy Tab S9 FE
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/522/309818/galaxy-tab-s9-fe-grey-thumb-600x600.jpg' WHERE product_id = '0de535db-f821-566a-8093-6288472bb016' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/522/309848/xiaomi-pad-6-blue-thumb-600x600.jpg' WHERE id = 'b34862a6-1101-55ed-9448-8d5767afd347'; -- Xiaomi Pad 6 128GB
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/522/309848/xiaomi-pad-6-blue-thumb-600x600.jpg' WHERE product_id = 'b34862a6-1101-55ed-9448-8d5767afd347' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://images.ctfassets.net/javen7msabdh/7Jo3yt8L1EKkGWPe8gK8tK/ba408004ee0cd7fc35126aba4ead321a/major-v-cream-front-desktop-1.jpeg?w=1200&fm=jpg&q=85' WHERE id = '99943638-b747-5ada-b10a-bed9bf25563c'; -- Marshall Major V
UPDATE product_images SET url = 'https://images.ctfassets.net/javen7msabdh/7Jo3yt8L1EKkGWPe8gK8tK/ba408004ee0cd7fc35126aba4ead321a/major-v-cream-front-desktop-1.jpeg?w=1200&fm=jpg&q=85' WHERE product_id = '99943638-b747-5ada-b10a-bed9bf25563c' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.tgdd.vn/Products/Images/7077/310858/samsung-galaxy-watch6-classic-47-mm-bac-ksp-600x600.jpg' WHERE id = 'b6b38a23-5b37-5049-becc-0000c5d5c2bf'; -- Samsung Galaxy Watch6 Classic 47mm
UPDATE product_images SET url = 'https://cdn.tgdd.vn/Products/Images/7077/310858/samsung-galaxy-watch6-classic-47-mm-bac-ksp-600x600.jpg' WHERE product_id = 'b6b38a23-5b37-5049-becc-0000c5d5c2bf' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://i02.appmifile.com/563_operatorx_operatorx_opx/18/11/2024/f8bb3a1bb9990d8d5dde34cdbc9b9af7.png' WHERE id = 'ae97f3e8-4c13-525e-8879-ca615910a29b'; -- Xiaomi Watch S3
UPDATE product_images SET url = 'https://i02.appmifile.com/563_operatorx_operatorx_opx/18/11/2024/f8bb3a1bb9990d8d5dde34cdbc9b9af7.png' WHERE product_id = 'ae97f3e8-4c13-525e-8879-ca615910a29b' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://www.amazfit.com/cdn/shop/products/3_f704d6b8-309b-4fbe-a131-12477f5b080a_grande.jpg?v=1671676228' WHERE id = '35bb84e1-87b2-5a23-997e-a117dc87d48f'; -- Amazfit GTR 4
UPDATE product_images SET url = 'https://www.amazfit.com/cdn/shop/products/3_f704d6b8-309b-4fbe-a131-12477f5b080a_grande.jpg?v=1671676228' WHERE product_id = '35bb84e1-87b2-5a23-997e-a117dc87d48f' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://cdn.shopify.com/s/files/1/0493/9834/9974/products/A2663111-Anker_715_Charger_Nano_II_65W.png?v=1767756360' WHERE id = '63e2ed13-d51c-57a7-ad07-9eedaa647bcf'; -- Anker Sạc nhanh GaN 65W
UPDATE product_images SET url = 'https://cdn.shopify.com/s/files/1/0493/9834/9974/products/A2663111-Anker_715_Charger_Nano_II_65W.png?v=1767756360' WHERE product_id = '63e2ed13-d51c-57a7-ad07-9eedaa647bcf' AND display_order IN (1, 2);
UPDATE products SET thumbnail_url = 'https://www.keychron.com/cdn/shop/products/Keychron-K2-Pro-QMK-VIA-Wireless-Mechanical-Keyboard-for-Mac-Windows-PBT-keycaps-PCB-screw-in-stabilizer-hot-swappable-red-switch_7f3edd88-59f3-4516-b953-05cdc3d3bece.jpg?crop=center&height=1200&v=1671268326&width=1200' WHERE id = '108f437e-8b69-5d7c-b6eb-42d16c3b341a'; -- Keychron K2 Pro Wireless
UPDATE product_images SET url = 'https://www.keychron.com/cdn/shop/products/Keychron-K2-Pro-QMK-VIA-Wireless-Mechanical-Keyboard-for-Mac-Windows-PBT-keycaps-PCB-screw-in-stabilizer-hot-swappable-red-switch_7f3edd88-59f3-4516-b953-05cdc3d3bece.jpg?crop=center&height=1200&v=1671268326&width=1200' WHERE product_id = '108f437e-8b69-5d7c-b6eb-42d16c3b341a' AND display_order IN (1, 2);

-- No photograph of the exact model was confirmed for these; they keep the V3 placeholder,
-- which at least names the product correctly:
--   vivo V30e 5G
--   Nothing Phone (2a)
--   MacBook Air M3 13 inch 8GB/256GB
--   Dell XPS 13 9340 Core Ultra 7
--   ASUS Zenbook 14 OLED
--   Lenovo ThinkPad X1 Carbon Gen 12
--   HP Pavilion 15 Core i5
--   Acer Nitro V 15 RTX 4050
--   MSI Modern 14 C13M
--   Lenovo Tab P12
--   AirPods Pro 2 USB-C
--   AirPods 4
--   Sony WH-1000XM5
--   Sony WF-1000XM5
--   Bose QuietComfort Ultra
--   JBL Tune 770NC
--   SoundPEATS Air4 Pro
--   Apple Watch Series 9 45mm GPS
--   Apple Watch SE 2 44mm GPS
--   Garmin Forerunner 265
--   Anker PowerCore 20.000mAh 22.5W
--   UGREEN Cáp USB-C to USB-C 2m 100W
--   Baseus Giá đỡ điện thoại ô tô
--   Logitech MX Master 3S

-- Category tiles reuse a photograph from a product in that category.
UPDATE categories SET image_url = 'https://cdn.tgdd.vn/Products/Images/42/305658/iphone-15-pro-max-blue-thumbnew-600x600.jpg' WHERE id = '621a9347-0b52-58d5-ba30-9c524079231d'; -- dien-thoai
UPDATE categories SET image_url = 'https://www.apple.com/newsroom/images/2023/10/apple-unveils-new-macbook-pro-featuring-m3-chips/article/Apple-MacBook-Pro-top-view-231030_big.jpg.large_2x.jpg' WHERE id = '1cf6c2ff-e4cd-50c1-bcb8-39c22a711b9b'; -- laptop
UPDATE categories SET image_url = 'https://cdn.tgdd.vn/Products/Images/522/325513/ipad-pro-11-inch-m4-wifi-sliver-thumb-600x600.jpg' WHERE id = '155c3f45-7376-54b8-8fe4-9859afd0cd8e'; -- tablet
UPDATE categories SET image_url = 'https://images.ctfassets.net/javen7msabdh/7Jo3yt8L1EKkGWPe8gK8tK/ba408004ee0cd7fc35126aba4ead321a/major-v-cream-front-desktop-1.jpeg?w=1200&fm=jpg&q=85' WHERE id = '3a8e7aee-9455-52a3-be9a-3790e375821a'; -- tai-nghe
UPDATE categories SET image_url = 'https://cdn.tgdd.vn/Products/Images/7077/310858/samsung-galaxy-watch6-classic-47-mm-bac-ksp-600x600.jpg' WHERE id = '5e8b4230-0d61-5f2b-a468-5499e8e7331b'; -- dong-ho-thong-minh
UPDATE categories SET image_url = 'https://cdn.shopify.com/s/files/1/0493/9834/9974/products/A2663111-Anker_715_Charger_Nano_II_65W.png?v=1767756360' WHERE id = 'cec6d4cc-9d78-55d8-aaf7-35d3bcf87a9f'; -- phu-kien
