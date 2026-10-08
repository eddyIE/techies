-- A deeper set of reviews, so the AI review summary has something to summarise.
--
-- V7 left 24 reviews across 12 products and only 5 of those cleared the three-review
-- threshold the summary needs, so 58 of 63 products showed no summary at all.
--
-- Authored, not crawled. cellphones.com.vn does not expose reviews: none are
-- server-rendered on a product page, no review endpoint is referenced inline, and the
-- GraphQL API rejects every review field shape. Authoring is also the better source
-- here, because the summary's acceptance criteria are about content: a complaint that
-- several reviewers share has to survive a high average (SPEC-ai.md), and that only
-- gets exercised if it is planted on purpose. Each product below has a deliberate
-- shape -- agreed strengths, plus one or two gripes that recur across reviewers.
--
-- Regenerate with tools/crawler/gen_more_reviews.py. Do not edit by hand.

INSERT INTO product_reviews (id, order_item_id, product_id, user_id, author_name, rating, comment, created_at) VALUES
  -- iphone-15-pro-max-256gb: loved for camera and battery; several say it runs warm and is heavy
  ('322e7954-b5d9-5d2d-9c64-935fe17280fc', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Phạm Thuỳ Dung', 5, 'Camera quay 4K cực nét, màu da người tự nhiên. Pin trụ được một ngày dùng nặng.', NOW() - INTERVAL '46 days'),
  ('015fdc9f-223a-5aba-b050-f3d88d212b72', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Phan Trọng Nghĩa', 4, 'Máy rất tốt nhưng chơi game khoảng 20 phút là nóng vùng camera, phải hạ sáng.', NOW() - INTERVAL '41 days'),
  ('02983fcc-2af3-5f2b-ab0e-d6ed8863ca32', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Tạ Văn Hoà', 5, 'Lên từ 12 Pro thấy khác biệt rõ ở zoom 5x. Khung titan cầm chắc tay.', NOW() - INTERVAL '37 days'),
  ('6102e591-5eff-58f7-bbcd-443e3268cf04', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Huỳnh Công Danh', 4, 'Nặng thật, bỏ túi quần jeans hơi cấn. Bù lại pin và camera không có gì để chê.', NOW() - INTERVAL '30 days'),
  ('c068109b-9d9d-5f1f-9dd7-446b670c7be3', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Trần Thị Thu Hương', 3, 'Nóng khi sạc vừa dùng, hơi khó chịu. Các thứ khác thì ổn.', NOW() - INTERVAL '24 days'),
  ('07b7631e-460a-5d83-89bd-8eecd9bccd68', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Ngô Đức Thịnh', 5, 'Màn hình ngoài nắng vẫn rõ, loa to. Đáng tiền nếu dùng lâu dài.', NOW() - INTERVAL '15 days'),
  -- samsung-galaxy-s24-ultra: S Pen and screen praised; charging speed is the repeated gripe
  ('4105546a-f3cc-5a52-a162-764502e5bf20', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Cao Bá Lộc', 5, 'S Pen ghi chú họp rất tiện, không cần mang theo sổ nữa.', NOW() - INTERVAL '52 days'),
  ('9f95faf7-7519-55de-a77b-57d7a965ba99', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Nguyễn Thị Kim Oanh', 4, 'Màn hình chống phản xạ tốt nhất mình từng dùng. Sạc thì chậm so với máy Trung Quốc.', NOW() - INTERVAL '44 days'),
  ('e41e737b-486e-5bdc-a926-d3285ed1d55e', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Kiều Diễm Hằng', 4, 'Zoom 100x hơi ảo nhưng 10x thì dùng được thật. Sạc đầy mất gần 1 tiếng rưỡi.', NOW() - INTERVAL '36 days'),
  ('eed9e501-bd48-5dc0-94e5-89a1639730a5', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Hoàng Văn Kiên', 5, 'Hiệu năng quá dư, chơi game nặng không tụt khung hình.', NOW() - INTERVAL '28 days'),
  ('e5775fdc-c4aa-5629-a7a5-ba7b4b2e30ad', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Trịnh Hải Đăng', 3, 'Sạc chậm và không kèm củ sạc, mua thêm mất thêm tiền.', NOW() - INTERVAL '19 days'),
  ('c0ccd68e-1841-5af7-8b43-b44d24cd5fdc', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Lương Bảo Ngọc', 5, 'Khung titan nhẹ hơn bản trước, cầm một tay đỡ mỏi.', NOW() - INTERVAL '9 days'),
  -- iphone-16-pro-max: strong reviews; price is what reviewers keep flagging
  ('3f3de5fa-6660-5fe6-abf9-2e565bfb384e', NULL, '07324815-82b1-580d-b763-45dbb73655a4', NULL, 'Trương Gia Bảo', 5, 'Nút Camera Control dùng quen rồi thấy nhanh hơn mở app.', NOW() - INTERVAL '40 days'),
  ('1fab43f3-9f92-5ecc-8ca7-9e662ddf85d1', NULL, '07324815-82b1-580d-b763-45dbb73655a4', NULL, 'Nguyễn Hữu Phước', 4, 'Quay phim ổn định không cần gimbal. Giá thì vẫn là rào cản lớn.', NOW() - INTERVAL '33 days'),
  ('3881878a-f062-511a-bb9a-f4891a3106c1', NULL, '07324815-82b1-580d-b763-45dbb73655a4', NULL, 'Vũ Thị Lan Anh', 5, 'Pin tốt hơn bản 15 Pro Max rõ rệt, tối về còn 30%.', NOW() - INTERVAL '27 days'),
  ('26d47440-983c-59c3-a427-0101f6f5eb89', NULL, '07324815-82b1-580d-b763-45dbb73655a4', NULL, 'Lý Thanh Trúc', 4, 'Đẹp và nhanh, nhưng với giá này thì nên tặng kèm sạc.', NOW() - INTERVAL '20 days'),
  ('eaa158d8-54c1-5fcf-867f-57ef600bd170', NULL, '07324815-82b1-580d-b763-45dbb73655a4', NULL, 'Chu Tiến Dũng', 3, 'Nâng cấp từ 15 Pro Max thì không đáng tiền, chênh lệch ít.', NOW() - INTERVAL '12 days'),
  ('96e7dabf-3b87-50cf-aeb4-e3f1c8b4c3d0', NULL, '07324815-82b1-580d-b763-45dbb73655a4', NULL, 'Tô Nhật Nam', 5, 'Màn hình lớn xem phim rất thích, loa cũng hay.', NOW() - INTERVAL '5 days'),
  -- iphone-15-128gb: good value; the 60Hz screen comes up again and again
  ('8067be25-8375-5c4e-994c-5440cc9ee882', NULL, 'e0404394-59b0-5044-af93-6a93adc3af39', NULL, 'Nguyễn Hữu Phước', 5, 'Kích thước vừa tay, dùng một tay thoải mái. Pin đủ dùng một ngày.', NOW() - INTERVAL '48 days'),
  ('2c6c4d0c-994b-5755-b8c1-ede0b932865e', NULL, 'e0404394-59b0-5044-af93-6a93adc3af39', NULL, 'Vũ Thị Lan Anh', 3, 'Màn hình chỉ 60Hz, kéo thả thấy không mượt bằng máy Android cùng giá.', NOW() - INTERVAL '39 days'),
  ('03fd614d-1734-55c6-8acb-7e8edb9c6e5e', NULL, 'e0404394-59b0-5044-af93-6a93adc3af39', NULL, 'Lý Thanh Trúc', 4, 'Camera chụp ngày rất tốt. Hơi tiếc là màn hình không 120Hz.', NOW() - INTERVAL '31 days'),
  ('72e0378f-7542-580f-997a-abdac2958be2', NULL, 'e0404394-59b0-5044-af93-6a93adc3af39', NULL, 'Chu Tiến Dũng', 5, 'Chuyển sang USB-C tiện, dùng chung sạc với laptop.', NOW() - INTERVAL '23 days'),
  ('b6074f3e-70ae-597a-9b4f-0b0223c9e530', NULL, 'e0404394-59b0-5044-af93-6a93adc3af39', NULL, 'Tô Nhật Nam', 4, '128GB hơi ít nếu quay video nhiều, nên cân nhắc bản 256GB.', NOW() - INTERVAL '14 days'),
  ('a7c8d102-257a-558f-a85f-3baaeba6bc8a', NULL, 'e0404394-59b0-5044-af93-6a93adc3af39', NULL, 'Phạm Thuỳ Dung', 2, 'Màn 60Hz ở mức giá này là thiếu sót, mình thấy khó chấp nhận.', NOW() - INTERVAL '6 days'),
  -- samsung-galaxy-a55: best-value verdicts; bloatware and update speed are the complaints
  ('1e6f1306-397c-5e92-a52c-0b42b41a9ed3', NULL, '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609', NULL, 'Lê Hoàng Phúc', 5, 'Tầm giá này mà màn Super AMOLED 120Hz thì quá tốt.', NOW() - INTERVAL '50 days'),
  ('9188afe3-bf6c-528a-a30b-5ef6a754213c', NULL, '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609', NULL, 'Dương Mỹ Linh', 4, 'Pin hai ngày nếu dùng vừa. Hơi nhiều app cài sẵn phải xoá bớt.', NOW() - INTERVAL '42 days'),
  ('545e27a0-eb98-5b8d-a20a-3edc8d07e937', NULL, '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609', NULL, 'Mai Quỳnh Như', 4, 'Chụp ảnh ban ngày đẹp, ban đêm thì thường thôi.', NOW() - INTERVAL '34 days'),
  ('56e6b044-b5ca-5c82-93c6-14ff8c175357', NULL, '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609', NULL, 'Võ Hồng Nhung', 3, 'Máy ổn nhưng quảng cáo trong app hệ thống làm mình khó chịu.', NOW() - INTERVAL '25 days'),
  ('c9080796-4292-5ee3-b0c1-ddfbd4a083a4', NULL, '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609', NULL, 'Nguyễn Minh Quân', 5, 'Vỏ kim loại cầm chắc, không ọp ẹp như mấy máy cùng giá.', NOW() - INTERVAL '17 days'),
  ('7c8a1073-f0fc-5920-9f01-070f51cca968', NULL, '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609', NULL, 'Bùi Khánh Chi', 4, 'Giá tốt, chỉ mong Samsung cập nhật phần mềm nhanh hơn.', NOW() - INTERVAL '8 days'),
  ('f7dcd949-f888-5cea-9bff-9d759a163c6e', NULL, '0d464fa8-0db7-5be3-ab7f-8d0a54fe3609', NULL, 'Đỗ Thu Trang', 5, 'Mua cho mẹ dùng, pin lâu và chữ to dễ đọc.', NOW() - INTERVAL '3 days'),
  -- xiaomi-14-ultra: camera is the draw; the software experience is the recurring gripe
  ('69a89696-c51c-54d4-938d-64265c528c59', NULL, '46be6c64-3e42-5c1d-b3d9-bfaa69128c81', NULL, 'Tạ Văn Hoà', 5, 'Ống kính Leica cho màu ảnh rất riêng, chụp chân dung đẹp.', NOW() - INTERVAL '45 days'),
  ('1fac710c-5b50-55bd-b799-6b5f3d76c5d3', NULL, '46be6c64-3e42-5c1d-b3d9-bfaa69128c81', NULL, 'Huỳnh Công Danh', 4, 'Camera xuất sắc nhưng hệ điều hành còn quảng cáo, phải tắt thủ công.', NOW() - INTERVAL '38 days'),
  ('c1a30615-146d-5579-a6ab-ca1bd2731c91', NULL, '46be6c64-3e42-5c1d-b3d9-bfaa69128c81', NULL, 'Trần Thị Thu Hương', 5, 'Chụp đêm hơn hẳn mấy máy mình từng dùng, ít noise.', NOW() - INTERVAL '29 days'),
  ('be889800-c4df-5da7-b692-5f8d48ba4129', NULL, '46be6c64-3e42-5c1d-b3d9-bfaa69128c81', NULL, 'Ngô Đức Thịnh', 3, 'Phần mềm nhiều app rác, dùng một tuần mới dọn xong.', NOW() - INTERVAL '21 days'),
  ('963a987b-0486-5cec-85ee-eb73a055c8c3', NULL, '46be6c64-3e42-5c1d-b3d9-bfaa69128c81', NULL, 'Hà Minh Triết', 4, 'Sạc 90W nhanh kinh ngạc, 20 phút gần đầy.', NOW() - INTERVAL '13 days'),
  ('29535d9a-e0e4-5293-9e76-bab4afeff802', NULL, '46be6c64-3e42-5c1d-b3d9-bfaa69128c81', NULL, 'Lâm Tuấn Kiệt', 5, 'Cục camera dày nhưng bù lại chất ảnh thì không bàn.', NOW() - INTERVAL '4 days'),
  -- macbook-pro-m3-14: silence and battery praised; price and port count repeated
  ('5413ab4d-2508-5f3c-b39a-4981a53f57a7', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Lê Hoàng Phúc', 5, 'Dựng video 4K không nghe tiếng quạt, pin dùng gần hết ngày làm việc.', NOW() - INTERVAL '47 days'),
  ('b3421d47-7819-5a98-90ae-97b15831e996', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Dương Mỹ Linh', 4, 'Máy quá tốt, chỉ tiếc ít cổng, vẫn phải mang theo hub.', NOW() - INTERVAL '40 days'),
  ('72ef0440-48ef-5f0d-bf4d-c779f69d691d', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Mai Quỳnh Như', 5, 'Màn hình XDR xem màu rất chuẩn, làm thiết kế yên tâm.', NOW() - INTERVAL '32 days'),
  ('386e9c92-174d-5259-a063-5daeaedae4f6', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Võ Hồng Nhung', 4, 'Hiệu năng dư dùng. Giá cao nên phải cân nhắc kỹ cấu hình ngay từ đầu.', NOW() - INTERVAL '26 days'),
  ('27d17fa9-4336-5e3c-a884-4ef65efe7453', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Nguyễn Minh Quân', 3, 'Bản 16GB/512GB giá khá chát, nâng RAM thì đội giá thêm nhiều.', NOW() - INTERVAL '18 days'),
  ('e1c75436-7807-5d83-a3ab-57e28ed9b82f', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Bùi Khánh Chi', 5, 'Bàn phím và trackpad vẫn là tốt nhất trong các laptop mình dùng.', NOW() - INTERVAL '7 days'),
  -- macbook-air-m3-13: portability loved; 8GB RAM and base storage are the warnings
  ('f8c7724d-e552-5e36-8da0-454d2dcb55a1', NULL, '717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a', NULL, 'Phan Trọng Nghĩa', 5, 'Nhẹ, mỏng, bỏ balo đi làm cả ngày không mỏi vai.', NOW() - INTERVAL '44 days'),
  ('6d24b74a-5b0c-590c-8c1c-e3f4be2ba667', NULL, '717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a', NULL, 'Tạ Văn Hoà', 3, 'Bản 8GB RAM mở nhiều tab Chrome là thấy chậm, nên mua 16GB.', NOW() - INTERVAL '37 days'),
  ('485cd916-646b-5831-96b4-b48edd1d2626', NULL, '717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a', NULL, 'Huỳnh Công Danh', 5, 'Không quạt nên hoàn toàn im lặng, làm văn phòng rất thích.', NOW() - INTERVAL '30 days'),
  ('a859efb5-0114-57d6-a13b-f2cbccf9a856', NULL, '717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a', NULL, 'Trần Thị Thu Hương', 4, 'Pin thật sự tốt, hai ngày mới phải sạc. 256GB thì hơi ít.', NOW() - INTERVAL '22 days'),
  ('e2efc4db-ce31-55bc-ae6e-8a7916fb7af0', NULL, '717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a', NULL, 'Ngô Đức Thịnh', 4, 'Màn hình đẹp, loa hay hơn mong đợi với máy mỏng thế này.', NOW() - INTERVAL '16 days'),
  ('7fedb237-558d-55b4-a6d5-6a287a858f8a', NULL, '717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a', NULL, 'Hà Minh Triết', 2, '8GB RAM năm nay là không đủ, dùng Lightroom thấy khựng.', NOW() - INTERVAL '10 days'),
  -- dell-xps-13-9340: build and screen admired; heat and fan noise repeated
  ('97fd4b3d-c0cb-5718-890f-a03a72757c44', NULL, '2d4074a9-dd36-53ea-b664-7c0f84353787', NULL, 'Đỗ Thu Trang', 5, 'Thiết kế đẹp, viền màn hình mỏng, mang đi họp rất sang.', NOW() - INTERVAL '43 days'),
  ('00390616-ec81-532f-ae34-916632e26b0a', NULL, '2d4074a9-dd36-53ea-b664-7c0f84353787', NULL, 'Trương Gia Bảo', 3, 'Chạy nặng một lúc là quạt kêu và máy nóng vùng bàn phím.', NOW() - INTERVAL '35 days'),
  ('4130ef29-91f8-5ea0-a5e5-f33ca1298a60', NULL, '2d4074a9-dd36-53ea-b664-7c0f84353787', NULL, 'Nguyễn Hữu Phước', 4, 'Màn hình rất đẹp. Quạt ồn hơn mình nghĩ khi build code.', NOW() - INTERVAL '28 days'),
  ('66aba362-0eb4-5db3-a732-836adc86f603', NULL, '2d4074a9-dd36-53ea-b664-7c0f84353787', NULL, 'Vũ Thị Lan Anh', 4, 'Bàn phím gõ êm, touchpad rộng. Pin tạm được.', NOW() - INTERVAL '20 days'),
  ('245c97b3-c3e0-5067-9aaa-78592299fc6b', NULL, '2d4074a9-dd36-53ea-b664-7c0f84353787', NULL, 'Lý Thanh Trúc', 3, 'Nóng ở đùi khi để trên chân, không thoải mái lắm.', NOW() - INTERVAL '11 days'),
  -- acer-nitro-v15: performance per dong praised; weight, noise and battery are the trade
  ('62fd1a68-5100-5d02-88cd-0c574817c4a4', NULL, 'cd11b47f-fba0-5d05-b842-f2d586ec167b', NULL, 'Huỳnh Công Danh', 5, 'RTX 4050 chơi game tầm cao ở 1080p rất ổn trong tầm giá này.', NOW() - INTERVAL '46 days'),
  ('581a4f17-1a1a-5d87-89f8-5c1b97638c1a', NULL, 'cd11b47f-fba0-5d05-b842-f2d586ec167b', NULL, 'Trần Thị Thu Hương', 3, 'Quạt ồn khi chơi game, phải đeo tai nghe. Máy cũng khá nặng.', NOW() - INTERVAL '39 days'),
  ('9f490318-352c-5512-bdf8-00c13e419699', NULL, 'cd11b47f-fba0-5d05-b842-f2d586ec167b', NULL, 'Ngô Đức Thịnh', 4, 'Hiệu năng tốt, nhưng pin chỉ được khoảng 3 tiếng làm việc nhẹ.', NOW() - INTERVAL '31 days'),
  ('832efea3-67c5-582d-8d59-05ee2289c4fe', NULL, 'cd11b47f-fba0-5d05-b842-f2d586ec167b', NULL, 'Hà Minh Triết', 4, 'Nâng RAM và SSD dễ, tháo nắp lưng là làm được.', NOW() - INTERVAL '24 days'),
  ('71f6b99d-4c11-5265-a42e-7048fb4a4acc', NULL, 'cd11b47f-fba0-5d05-b842-f2d586ec167b', NULL, 'Lâm Tuấn Kiệt', 2, 'Nặng và dày, mang đi học mỗi ngày thì khá mệt.', NOW() - INTERVAL '15 days'),
  ('0cd8876b-7f1b-5ed3-8001-a6e191dcaf62', NULL, 'cd11b47f-fba0-5d05-b842-f2d586ec167b', NULL, 'Phùng Thanh Tâm', 5, 'Giá này khó tìm máy mạnh hơn, mình hài lòng.', NOW() - INTERVAL '6 days'),
  -- asus-zenbook-14-oled: OLED and weight loved; screen reflections keep coming up
  ('03d698dc-4db5-561a-ad8a-c827b80df74a', NULL, '143375fb-6a1a-5099-b224-bfb2203bf703', NULL, 'Bùi Khánh Chi', 5, 'Màn OLED xem phim quá đẹp, màu đen sâu.', NOW() - INTERVAL '41 days'),
  ('b27502c3-8668-54e7-9bac-dc9403ed07fa', NULL, '143375fb-6a1a-5099-b224-bfb2203bf703', NULL, 'Đỗ Thu Trang', 4, 'Nhẹ và pin tốt. Màn bóng nên ngồi gần cửa sổ bị phản chiếu.', NOW() - INTERVAL '34 days'),
  ('42f9f005-485b-5bf3-a90e-f2deb4caca76', NULL, '143375fb-6a1a-5099-b224-bfb2203bf703', NULL, 'Trương Gia Bảo', 4, 'Hoàn thiện kim loại chắc chắn, bản lề mở một tay được.', NOW() - INTERVAL '27 days'),
  ('53aea3c9-9df6-526e-b39b-51c5cc2d6b4a', NULL, '143375fb-6a1a-5099-b224-bfb2203bf703', NULL, 'Nguyễn Hữu Phước', 3, 'Màn gương phản chiếu nhiều, làm ngoài trời khó nhìn.', NOW() - INTERVAL '19 days'),
  ('910677c3-0e51-53ce-be7f-946d3aab3b37', NULL, '143375fb-6a1a-5099-b224-bfb2203bf703', NULL, 'Vũ Thị Lan Anh', 5, 'Có đủ cổng HDMI và USB-A, không cần hub.', NOW() - INTERVAL '9 days'),
  -- sony-wf-1000xm5: ANC is the star; case finish and price recur
  ('c0b292a1-a3c9-557d-b097-714408e0aa05', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Nguyễn Thị Kim Oanh', 5, 'Chống ồn tốt nhất trong tai nghe nhét tai mình từng dùng.', NOW() - INTERVAL '49 days'),
  ('0d236613-09e8-51f4-a11a-45a3c61acdca', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Kiều Diễm Hằng', 4, 'Âm thanh và chống ồn tuyệt vời. Hộp sạc dễ xước, sau một tháng đã thấy vết.', NOW() - INTERVAL '42 days'),
  ('9d10e121-9940-5590-b5cc-1370391084b5', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Hoàng Văn Kiên', 5, 'Đàm thoại rõ, người nghe không phàn nàn gì.', NOW() - INTERVAL '33 days'),
  ('e7264dcd-898d-54bc-841e-7c811c01d906', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Trịnh Hải Đăng', 4, 'Nhỏ gọn hơn bản XM4, đeo lâu không đau tai.', NOW() - INTERVAL '25 days'),
  ('ce234083-f8f6-5523-9ea5-115bb5491316', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Lương Bảo Ngọc', 3, 'Giá cao so với mặt bằng, và vỏ hộp bám xước nhanh.', NOW() - INTERVAL '18 days'),
  ('b2bbcbe9-b5ef-5b3c-aecd-1a275076de6d', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Đinh Phương Mai', 5, 'Ứng dụng tuỳ chỉnh EQ chi tiết, dùng rất hài lòng.', NOW() - INTERVAL '8 days'),
  -- sony-wh-1000xm5: comfort and ANC praised; not folding is the repeated annoyance
  ('ad348f2b-ed9e-5a58-af2e-daee53931e34', NULL, 'b32865ff-c545-56ce-84fc-50147aa5ff3f', NULL, 'Lâm Tuấn Kiệt', 5, 'Đeo 5 tiếng liền vẫn êm, không bị nóng tai.', NOW() - INTERVAL '45 days'),
  ('dc6edef5-869c-5cf4-a10f-d5225c747640', NULL, 'b32865ff-c545-56ce-84fc-50147aa5ff3f', NULL, 'Phùng Thanh Tâm', 3, 'Chống ồn đỉnh nhưng không gập được, hộp đựng to khó nhét balo.', NOW() - INTERVAL '38 days'),
  ('aa482fb3-2775-5ca6-8195-59b22dbe3a27', NULL, 'b32865ff-c545-56ce-84fc-50147aa5ff3f', NULL, 'Đặng Quốc Hưng', 5, 'Chất âm cân bằng, nghe nhạc acoustic rất hay.', NOW() - INTERVAL '30 days'),
  ('8fe7b741-25e3-5a8d-a24f-14d6fac4e07c', NULL, 'b32865ff-c545-56ce-84fc-50147aa5ff3f', NULL, 'Cao Bá Lộc', 4, 'Rất tốt, chỉ tiếc bản này bỏ cơ chế gập của XM4.', NOW() - INTERVAL '22 days'),
  ('0d5315b7-dcfe-5329-a476-27566c58aec3', NULL, 'b32865ff-c545-56ce-84fc-50147aa5ff3f', NULL, 'Nguyễn Thị Kim Oanh', 4, 'Pin dùng cả tuần đi làm mới sạc lại.', NOW() - INTERVAL '14 days'),
  ('57fc1ad3-65aa-5a12-8c9c-be5c4920c9b4', NULL, 'b32865ff-c545-56ce-84fc-50147aa5ff3f', NULL, 'Kiều Diễm Hằng', 5, 'Chuyển thiết bị tự động nhanh, tiện khi vừa dùng máy tính vừa nghe điện thoại.', NOW() - INTERVAL '5 days'),
  -- airpods-pro-2-usbc: ecosystem convenience loved; fit discomfort recurs for some
  ('55784123-a40d-59d6-af6e-928bf075774f', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Lê Hoàng Phúc', 5, 'Dùng với iPhone và iPad thì quá mượt, chuyển qua lại tự động.', NOW() - INTERVAL '47 days'),
  ('4aaec1f0-0cb3-5c93-a057-d2c36965438e', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Dương Mỹ Linh', 3, 'Chống ồn tốt nhưng đeo quá 2 tiếng là tai mình bị đau.', NOW() - INTERVAL '40 days'),
  ('5600088a-7829-5e6f-a918-fbffc4ebfbb7', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Mai Quỳnh Như', 5, 'Chế độ xuyên âm tự nhiên như không đeo gì.', NOW() - INTERVAL '32 days'),
  ('92a709e9-38a6-5f90-9291-e3d090dd82a7', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Võ Hồng Nhung', 4, 'Rất tiện, chỉ là giá cao và đeo lâu hơi mỏi tai.', NOW() - INTERVAL '23 days'),
  ('e4dd6224-ee67-5594-8f8b-d772c4d3b548', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Nguyễn Minh Quân', 5, 'Đổi sang USB-C dùng chung sạc với máy tính, gọn gàng.', NOW() - INTERVAL '15 days'),
  ('7280b140-b533-5fe6-b84f-a6a6fc27644e', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Bùi Khánh Chi', 4, 'Tìm tai nghe qua Find My cứu mình mấy lần rồi.', NOW() - INTERVAL '7 days'),
  -- jbl-tune-770nc: value verdicts; plastic build is the shared criticism
  ('83483786-8b15-52ad-a783-2a1e9421673b', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Nguyễn Thị Kim Oanh', 5, 'Pin quảng cáo 70 tiếng mà dùng thật cũng gần vậy, quá tốt.', NOW() - INTERVAL '44 days'),
  ('cd7fccdd-ca20-5b46-a6fa-1ed3ddcbacd6', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Kiều Diễm Hằng', 3, 'Giá rẻ có chống ồn là được, nhưng nhựa vỏ cảm giác mỏng, sợ gãy bản lề.', NOW() - INTERVAL '36 days'),
  ('c6b7b833-a918-55d7-84de-3a7409fc9448', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Hoàng Văn Kiên', 4, 'Bass mạnh, nghe nhạc EDM rất vui tai.', NOW() - INTERVAL '29 days'),
  ('ea9c48d8-bc90-56a3-923a-3f966ddd8bcc', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Trịnh Hải Đăng', 4, 'Tầm giá này khó đòi hỏi hơn. Chỉ lo độ bền của nhựa.', NOW() - INTERVAL '21 days'),
  ('794f3674-7227-55c8-a1a5-b2ab103d8f53', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Lương Bảo Ngọc', 5, 'Gập gọn mang đi du lịch tiện.', NOW() - INTERVAL '12 days'),
  ('2359a314-74ea-55ef-851f-d383535d9a73', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Đinh Phương Mai', 3, 'Chống ồn chỉ ở mức vừa, ồn động cơ xe buýt vẫn nghe rõ.', NOW() - INTERVAL '4 days'),
  -- bose-quietcomfort-ultra: ANC and comfort top marks; price and app stability recur
  ('2056514e-c023-5cf9-b165-65680a3e9315', NULL, '8e693a9b-d65c-522c-ab1e-badcc58bc823', NULL, 'Lương Bảo Ngọc', 5, 'Chống ồn trên máy bay thì Bose vẫn là số một.', NOW() - INTERVAL '42 days'),
  ('94cb1887-e82a-51fc-b35a-c98c130c5251', NULL, '8e693a9b-d65c-522c-ab1e-badcc58bc823', NULL, 'Đinh Phương Mai', 4, 'Rất êm và nhẹ đầu. App thỉnh thoảng mất kết nối, phải mở lại.', NOW() - INTERVAL '34 days'),
  ('d6dcb4e4-0351-580d-b336-8735a354a645', NULL, '8e693a9b-d65c-522c-ab1e-badcc58bc823', NULL, 'Lê Hoàng Phúc', 5, 'Âm thanh không gian nghe phim rất hay.', NOW() - INTERVAL '26 days'),
  ('48fc2eba-c274-5677-a355-2500c1b2ee0f', NULL, '8e693a9b-d65c-522c-ab1e-badcc58bc823', NULL, 'Dương Mỹ Linh', 3, 'Giá rất cao, và phần mềm chưa ổn định bằng Sony.', NOW() - INTERVAL '17 days'),
  ('a3202764-2437-5486-9975-0d299d5a8705', NULL, '8e693a9b-d65c-522c-ab1e-badcc58bc823', NULL, 'Mai Quỳnh Như', 5, 'Đeo đi làm cả ngày không đau tai.', NOW() - INTERVAL '8 days'),
  -- soundpeats-air4-pro: cheap and cheerful; call quality is the weak point several name
  ('428e35aa-f722-5c62-85b6-033572356008', NULL, '08f2421a-f186-5270-be82-390e8f6376fe', NULL, 'Đặng Quốc Hưng', 5, 'Giá hơn một triệu mà có chống ồn, quá đáng tiền.', NOW() - INTERVAL '39 days'),
  ('89665a7c-7b95-5e64-a4f2-d3b5734c6bf9', NULL, '08f2421a-f186-5270-be82-390e8f6376fe', NULL, 'Cao Bá Lộc', 3, 'Nghe nhạc ổn nhưng gọi điện ngoài đường thì mic bắt nhiều tiếng ồn.', NOW() - INTERVAL '31 days'),
  ('42f3a409-1dfe-5d88-bd44-a0516544f79f', NULL, '08f2421a-f186-5270-be82-390e8f6376fe', NULL, 'Nguyễn Thị Kim Oanh', 4, 'Kết nối nhanh, độ trễ thấp khi chơi game.', NOW() - INTERVAL '24 days'),
  ('58c307a4-726b-5498-82a5-263d281e688f', NULL, '08f2421a-f186-5270-be82-390e8f6376fe', NULL, 'Kiều Diễm Hằng', 4, 'Tốt trong tầm giá, chỉ mic là điểm yếu.', NOW() - INTERVAL '16 days'),
  ('04675b09-5b52-5d34-8414-5bca7f43a9df', NULL, '08f2421a-f186-5270-be82-390e8f6376fe', NULL, 'Hoàng Văn Kiên', 5, 'Pin dùng được 5-6 tiếng liên tục, đủ cho một ngày.', NOW() - INTERVAL '6 days'),
  -- apple-watch-series-9-45: health tracking praised; daily charging is the universal gripe
  ('75455ebe-5955-59d7-ad84-56e398d1b2c8', NULL, '8f2ab3b5-6a9a-5471-9172-b721e82fcc92', NULL, 'Đinh Phương Mai', 5, 'Đo nhịp tim và SpO2 khớp với máy ở phòng khám.', NOW() - INTERVAL '43 days'),
  ('e47bc607-6e55-5a5d-80df-0710c9b7064a', NULL, '8f2ab3b5-6a9a-5471-9172-b721e82fcc92', NULL, 'Lê Hoàng Phúc', 3, 'Tính năng đầy đủ nhưng phải sạc mỗi ngày, đi công tác hơi bất tiện.', NOW() - INTERVAL '35 days'),
  ('3e5d43bc-b6ef-5eef-afaa-ef59ef7501a3', NULL, '8f2ab3b5-6a9a-5471-9172-b721e82fcc92', NULL, 'Dương Mỹ Linh', 5, 'Nhắc đứng lên và theo dõi giấc ngủ giúp mình đổi thói quen thật.', NOW() - INTERVAL '28 days'),
  ('26cb37c9-c61b-5790-9173-3b918d035d50', NULL, '8f2ab3b5-6a9a-5471-9172-b721e82fcc92', NULL, 'Mai Quỳnh Như', 4, 'Màn hình sáng, nhìn ngoài nắng rõ. Pin thì chỉ một ngày.', NOW() - INTERVAL '20 days'),
  ('a7f4c643-225b-5cc1-8752-5f84b9909d63', NULL, '8f2ab3b5-6a9a-5471-9172-b721e82fcc92', NULL, 'Võ Hồng Nhung', 4, 'Double Tap dùng khi tay bận khá hay.', NOW() - INTERVAL '11 days'),
  ('d76b7693-69fb-55ce-86ef-6d4cb2bd68a6', NULL, '8f2ab3b5-6a9a-5471-9172-b721e82fcc92', NULL, 'Nguyễn Minh Quân', 3, 'Pin một ngày là điểm mình thất vọng nhất.', NOW() - INTERVAL '3 days'),
  -- samsung-watch6-classic: rotating bezel loved; battery and scratches recur
  ('5583ece4-55ae-5ea6-b512-659d3a0c9c3b', NULL, 'b6b38a23-5b37-5049-becc-0000c5d5c2bf', NULL, 'Nguyễn Minh Quân', 5, 'Vòng bezel xoay dùng rất thích, chính xác hơn chạm màn hình.', NOW() - INTERVAL '41 days'),
  ('b3cfa4cb-4259-5338-af9a-30a64c44fe55', NULL, 'b6b38a23-5b37-5049-becc-0000c5d5c2bf', NULL, 'Bùi Khánh Chi', 4, 'Đẹp và sang, nhưng pin cũng chỉ một ngày rưỡi.', NOW() - INTERVAL '33 days'),
  ('bd0a1508-2f04-5aad-918f-a0afbfd32db5', NULL, 'b6b38a23-5b37-5049-becc-0000c5d5c2bf', NULL, 'Đỗ Thu Trang', 4, 'Theo dõi sức khoẻ đầy đủ, đồng bộ với điện thoại Samsung mượt.', NOW() - INTERVAL '25 days'),
  ('0d84bc26-fc34-58b6-b3f1-9043a3e1ce8f', NULL, 'b6b38a23-5b37-5049-becc-0000c5d5c2bf', NULL, 'Trương Gia Bảo', 3, 'Viền bezel bị xước nhẹ sau một tháng dù mình cẩn thận.', NOW() - INTERVAL '18 days'),
  ('dde9af10-a7ba-55ee-ac06-8ab512eaf69f', NULL, 'b6b38a23-5b37-5049-becc-0000c5d5c2bf', NULL, 'Nguyễn Hữu Phước', 5, 'Mặt đồng hồ nhiều lựa chọn, thay theo trang phục được.', NOW() - INTERVAL '9 days'),
  -- garmin-forerunner-265: GPS and battery excellent; the app and looks draw criticism
  ('65e9295b-0abc-5f73-94aa-a9849d13ac1f', NULL, 'c53ee39e-8596-5992-b536-199cfb87fa4c', NULL, 'Đặng Quốc Hưng', 5, 'Pin hai tuần, chạy marathon không lo hết giữa đường.', NOW() - INTERVAL '40 days'),
  ('da6c0c4e-b3e9-58ce-bc44-e59ff68128c5', NULL, 'c53ee39e-8596-5992-b536-199cfb87fa4c', NULL, 'Cao Bá Lộc', 4, 'GPS bắt rất nhanh và chính xác. App Garmin thì hơi rối.', NOW() - INTERVAL '32 days'),
  ('d1c8031c-3fa3-5771-b9c6-a58f38159de5', NULL, 'c53ee39e-8596-5992-b536-199cfb87fa4c', NULL, 'Nguyễn Thị Kim Oanh', 5, 'Số liệu tập luyện chi tiết, hữu ích cho người chạy nghiêm túc.', NOW() - INTERVAL '24 days'),
  ('00b59c45-aa28-57dc-b5ca-45037ac7b745', NULL, 'c53ee39e-8596-5992-b536-199cfb87fa4c', NULL, 'Kiều Diễm Hằng', 3, 'Phần mềm và giao diện app chưa thân thiện, mất thời gian làm quen.', NOW() - INTERVAL '15 days'),
  ('c3b198c5-c148-547b-822f-0cdb20b832d3', NULL, 'c53ee39e-8596-5992-b536-199cfb87fa4c', NULL, 'Hoàng Văn Kiên', 4, 'Thiết kế hơi thể thao, đi làm mặc áo sơ mi thấy không phù hợp.', NOW() - INTERVAL '7 days'),
  -- ipad-pro-m4-11: display and performance loved; total cost with accessories recurs
  ('2e3b6632-e890-5b77-af2b-14bfd45ff233', NULL, '9db2c05c-f0ef-5d7c-b075-377083159bf2', NULL, 'Lý Thanh Trúc', 5, 'Màn hình OLED Tandem xem phim HDR quá đẹp.', NOW() - INTERVAL '38 days'),
  ('1fa0c418-2a3d-5bcf-a2ec-3bd04bc142d9', NULL, '9db2c05c-f0ef-5d7c-b075-377083159bf2', NULL, 'Chu Tiến Dũng', 4, 'Mỏng và nhanh. Nhưng cộng thêm bút và bàn phím thì giá đội lên nhiều.', NOW() - INTERVAL '30 days'),
  ('03895be2-393a-572f-9ec3-b037386d0697', NULL, '9db2c05c-f0ef-5d7c-b075-377083159bf2', NULL, 'Tô Nhật Nam', 5, 'Vẽ với Apple Pencil Pro độ trễ gần như không có.', NOW() - INTERVAL '23 days'),
  ('6a07e8f3-d84c-5695-9780-eef12480d476', NULL, '9db2c05c-f0ef-5d7c-b075-377083159bf2', NULL, 'Phạm Thuỳ Dung', 3, 'Mạnh quá mức cần thiết cho iPadOS, hơi tiếc tiền.', NOW() - INTERVAL '14 days'),
  ('37977169-2f62-561b-85b6-d2a33c9901f2', NULL, '9db2c05c-f0ef-5d7c-b075-377083159bf2', NULL, 'Phan Trọng Nghĩa', 4, 'Loa bốn hướng nghe rất hay khi xem phim.', NOW() - INTERVAL '6 days'),
  -- ipad-air-m2-11: sweet-spot verdicts; accessories not included is the shared note
  ('23778c9a-6347-51e1-8a14-7f3350e3f1fe', NULL, 'f9f208fa-0a89-5e02-abcc-81521fd08651', NULL, 'Chu Tiến Dũng', 5, 'Hiệu năng dư cho học tập và giải trí, giá dễ chịu hơn bản Pro.', NOW() - INTERVAL '37 days'),
  ('62ac673e-bf51-5f49-a385-2f8879fbee5f', NULL, 'f9f208fa-0a89-5e02-abcc-81521fd08651', NULL, 'Tô Nhật Nam', 4, 'Rất tốt, chỉ là bút phải mua riêng khá đắt.', NOW() - INTERVAL '29 days'),
  ('797f187f-4b6b-5847-9e9c-a95e6739d8ea', NULL, 'f9f208fa-0a89-5e02-abcc-81521fd08651', NULL, 'Phạm Thuỳ Dung', 5, 'Nhẹ, cầm đọc sách lâu không mỏi tay.', NOW() - INTERVAL '21 days'),
  ('80d63abd-ede1-5749-86f5-c27d72e9e831', NULL, 'f9f208fa-0a89-5e02-abcc-81521fd08651', NULL, 'Phan Trọng Nghĩa', 4, 'Màn 11 inch vừa đủ để chia hai cửa sổ làm việc.', NOW() - INTERVAL '13 days'),
  ('6f8bde10-4744-5570-9b1d-35c340f47f0b', NULL, 'f9f208fa-0a89-5e02-abcc-81521fd08651', NULL, 'Tạ Văn Hoà', 3, 'Không kèm sạc nhanh, sạc bằng củ cũ thì lâu.', NOW() - INTERVAL '5 days'),
  -- xiaomi-pad-6: value and screen praised; Android tablet apps are the complaint
  ('1bd3776c-4e63-51d4-a5b0-5a5152e23d61', NULL, 'b34862a6-1101-55ed-9448-8d5767afd347', NULL, 'Kiều Diễm Hằng', 5, 'Màn 144Hz mượt, giá chỉ bằng nửa iPad cùng kích thước.', NOW() - INTERVAL '36 days'),
  ('60266144-32a0-5637-8707-ec52e1b78be0', NULL, 'b34862a6-1101-55ed-9448-8d5767afd347', NULL, 'Hoàng Văn Kiên', 3, 'Máy tốt nhưng nhiều app Android chưa tối ưu cho tablet, hiển thị như điện thoại.', NOW() - INTERVAL '28 days'),
  ('927cc0ed-ea8b-5476-aa01-e7d58aaa563e', NULL, 'b34862a6-1101-55ed-9448-8d5767afd347', NULL, 'Trịnh Hải Đăng', 4, 'Loa hay, xem YouTube thoải mái. Pin ổn.', NOW() - INTERVAL '20 days'),
  ('a27448fb-09a2-5495-9eff-2bc42cb1df87', NULL, 'b34862a6-1101-55ed-9448-8d5767afd347', NULL, 'Lương Bảo Ngọc', 4, 'Đáng tiền, chỉ là hệ sinh thái app còn thiếu.', NOW() - INTERVAL '12 days'),
  ('8c1ee438-19c8-5208-8781-2860d30c9695', NULL, 'b34862a6-1101-55ed-9448-8d5767afd347', NULL, 'Đinh Phương Mai', 5, 'Vỏ kim loại chắc chắn, không rẻ tiền như mình nghĩ.', NOW() - INTERVAL '4 days'),
  -- smart-tivi-samsung-neo-qled-75qn80f-4k-75-inch-2025: picture quality loved; the smart platform is the gripe
  ('c03ae833-f37a-52cf-aa1c-e52640492922', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Nguyễn Thị Kim Oanh', 5, 'Màu sắc và độ tương phản rất tốt, xem bóng đá như ở sân.', NOW() - INTERVAL '39 days'),
  ('ad537f23-7e92-56ab-8782-81de6412444e', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Kiều Diễm Hằng', 4, 'Hình ảnh xuất sắc. Nhưng giao diện Tizen có quảng cáo ở trang chủ.', NOW() - INTERVAL '31 days'),
  ('cc67c998-a055-5e08-a1b7-6d1de50fd193', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Hoàng Văn Kiên', 5, 'Chơi game PS5 ở 120Hz mượt, độ trễ thấp.', NOW() - INTERVAL '23 days'),
  ('f710b1de-29de-5057-8535-f1cb8f63ce48', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Trịnh Hải Đăng', 3, 'Remote ít nút, điều khiển bằng giọng nói tiếng Việt chưa chính xác.', NOW() - INTERVAL '16 days'),
  ('93968793-d412-5a33-a6cc-52f5228f50b8', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Lương Bảo Ngọc', 4, 'Lắp đặt tận nhà nhanh, kỹ thuật viên cẩn thận.', NOW() - INTERVAL '8 days'),
  ('91f9209e-8ef6-58d2-bd22-3b9b906d9b98', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Đinh Phương Mai', 5, 'Chống loá tốt, phòng nhiều cửa sổ vẫn xem được ban ngày.', NOW() - INTERVAL '2 days'),
  -- logitech-mx-master-3s: comfort and silence praised; size for small hands recurs
  ('ceb7e1fd-0806-58d5-9f39-9ea3c132ee24', NULL, '56f2e956-2ca9-568d-93b5-c2266820b87d', NULL, 'Tô Nhật Nam', 5, 'Click êm hẳn so với bản 3, làm việc khuya không ồn.', NOW() - INTERVAL '35 days'),
  ('0794cd53-7c75-560c-8959-1d00e68acc8a', NULL, '56f2e956-2ca9-568d-93b5-c2266820b87d', NULL, 'Phạm Thuỳ Dung', 4, 'Rất thoải mái cho tay to. Tay nhỏ thì có thể thấy quá khổ.', NOW() - INTERVAL '27 days'),
  ('c1ad6259-170d-5c90-9f99-58a14b986694', NULL, '56f2e956-2ca9-568d-93b5-c2266820b87d', NULL, 'Phan Trọng Nghĩa', 5, 'Con lăn ngang cuộn Excel tiện kinh khủng.', NOW() - INTERVAL '19 days'),
  ('5b33edb0-d390-534d-a41b-81f6bce1f2b6', NULL, '56f2e956-2ca9-568d-93b5-c2266820b87d', NULL, 'Tạ Văn Hoà', 3, 'Chuột khá lớn và nặng, cầm cả ngày mình thấy mỏi.', NOW() - INTERVAL '11 days'),
  ('bf6078b6-86db-581c-8fda-3b64459d387f', NULL, '56f2e956-2ca9-568d-93b5-c2266820b87d', NULL, 'Huỳnh Công Danh', 5, 'Kết nối ba thiết bị, chuyển qua lại một nút.', NOW() - INTERVAL '3 days'),
  -- keychron-k2-pro: build and hot-swap loved; height needs a wrist rest, several say
  ('aefbef3e-d456-5900-8eb3-dc722f671f5b', NULL, '108f437e-8b69-5d7c-b6eb-42d16c3b341a', NULL, 'Lương Bảo Ngọc', 5, 'Khung nhôm nặng, gõ rất chắc tay và êm.', NOW() - INTERVAL '34 days'),
  ('807ffdf6-4ba1-5e77-b8b7-75ffa68f6e93', NULL, '108f437e-8b69-5d7c-b6eb-42d16c3b341a', NULL, 'Đinh Phương Mai', 4, 'Hot-swap đổi switch dễ. Bàn phím khá cao, nên mua kê tay.', NOW() - INTERVAL '26 days'),
  ('e681bd1d-431e-5d2e-bc97-9e0b6bdd2422', NULL, '108f437e-8b69-5d7c-b6eb-42d16c3b341a', NULL, 'Lê Hoàng Phúc', 5, 'Kết nối Bluetooth ba thiết bị ổn định, không trễ.', NOW() - INTERVAL '18 days'),
  ('8ee1f674-12b8-5cd7-a0b9-31d53543ac39', NULL, '108f437e-8b69-5d7c-b6eb-42d16c3b341a', NULL, 'Dương Mỹ Linh', 3, 'Độ cao phím làm mỏi cổ tay nếu gõ lâu mà không kê.', NOW() - INTERVAL '10 days'),
  ('b1576783-a79b-5acc-96e2-939b3cf05837', NULL, '108f437e-8b69-5d7c-b6eb-42d16c3b341a', NULL, 'Mai Quỳnh Như', 4, 'Đèn nền đẹp, pin dùng cả tuần.', NOW() - INTERVAL '2 days'),
  -- anker-powerbank-20000: capacity and charge speed praised; bulk is the trade
  ('b418451b-d9ed-5c65-9afe-2ce5a7aaed68', NULL, '67f7cce7-b683-554c-8098-506e445a86df', NULL, 'Bùi Khánh Chi', 5, 'Sạc đầy iPhone được ba lần, đi du lịch rất yên tâm.', NOW() - INTERVAL '33 days'),
  ('f0adbd27-7f79-515a-b0fe-062de7eadbfb', NULL, '67f7cce7-b683-554c-8098-506e445a86df', NULL, 'Đỗ Thu Trang', 3, 'Dung lượng tốt nhưng nặng, bỏ túi áo thì không được.', NOW() - INTERVAL '25 days'),
  ('7bbc0b25-c9d6-5dd0-befc-b7af796f01c5', NULL, '67f7cce7-b683-554c-8098-506e445a86df', NULL, 'Trương Gia Bảo', 4, 'Sạc nhanh 22.5W cho điện thoại Android, dùng tốt.', NOW() - INTERVAL '17 days'),
  ('a42c2072-231d-5236-a102-55fb9c34cf36', NULL, '67f7cce7-b683-554c-8098-506e445a86df', NULL, 'Nguyễn Hữu Phước', 4, 'Chắc chắn, dùng một năm chưa chai pin rõ rệt.', NOW() - INTERVAL '9 days'),
  ('f3eeb18b-dc8f-5b77-a1da-1eb09068d562', NULL, '67f7cce7-b683-554c-8098-506e445a86df', NULL, 'Vũ Thị Lan Anh', 5, 'Có màn hình báo phần trăm, biết còn bao nhiêu mà tính.', NOW() - INTERVAL '1 days'),
  -- anker-sac-nhanh-65w: size and versatility praised; it warms up, several note
  ('04b53b26-5100-5e86-9838-144b0522c036', NULL, '63e2ed13-d51c-57a7-ad07-9eedaa647bcf', NULL, 'Võ Hồng Nhung', 5, 'Nhỏ bằng nửa củ sạc Mac mà sạc được cả laptop.', NOW() - INTERVAL '32 days'),
  ('180edb7e-0c44-592b-ae44-e0b0607fdb0c', NULL, '63e2ed13-d51c-57a7-ad07-9eedaa647bcf', NULL, 'Nguyễn Minh Quân', 4, 'Ba cổng tiện, sạc cùng lúc điện thoại và tai nghe. Có hơi nóng khi sạc laptop.', NOW() - INTERVAL '24 days'),
  ('763c7bea-1192-5b7c-b1a3-ae0c2c885ef6', NULL, '63e2ed13-d51c-57a7-ad07-9eedaa647bcf', NULL, 'Bùi Khánh Chi', 5, 'Chân cắm gập được, mang đi công tác gọn.', NOW() - INTERVAL '16 days'),
  ('4904a956-0c41-5385-b790-fa5ca3c8c253', NULL, '63e2ed13-d51c-57a7-ad07-9eedaa647bcf', NULL, 'Đỗ Thu Trang', 3, 'Sạc laptop thì củ nóng khá rõ, mình hơi lo về lâu dài.', NOW() - INTERVAL '7 days'),
  -- tu-lanh-lg-inverter-side-by-side-gr-b256bl-519-lit: space and cooling praised; night noise recurs
  ('8e4872b8-5e48-5285-a874-238fc6737c92', NULL, '70c37824-00a9-5289-8f5c-95c1272747b0', NULL, 'Mai Quỳnh Như', 5, '519 lít chứa đồ cho gia đình 5 người thoải mái.', NOW() - INTERVAL '31 days'),
  ('a09d53fc-873f-5361-beac-3fe243d390cc', NULL, '70c37824-00a9-5289-8f5c-95c1272747b0', NULL, 'Võ Hồng Nhung', 4, 'Làm lạnh nhanh và đều. Ban đêm nghe tiếng máy nén nếu phòng yên tĩnh.', NOW() - INTERVAL '23 days'),
  ('256c2925-525b-5fe8-b1ea-96c8f5f2251e', NULL, '70c37824-00a9-5289-8f5c-95c1272747b0', NULL, 'Nguyễn Minh Quân', 5, 'Ngăn đá rộng, lấy đồ không phải xếp chồng.', NOW() - INTERVAL '15 days'),
  ('f66f574c-e2ad-5d08-a32b-b408df746a7e', NULL, '70c37824-00a9-5289-8f5c-95c1272747b0', NULL, 'Bùi Khánh Chi', 3, 'Hơi ồn vào đêm, nhà mình bếp liền phòng ngủ nên thấy rõ.', NOW() - INTERVAL '6 days'),
  -- may-giat-samsung-bespoke-14kg-inverter-ww14bb944dgbsv: quiet and clean; cycle length is the shared note
  ('bb8d537c-505e-54c2-9322-bfba3d97bfc3', NULL, '1ed0d41d-a9f3-57d0-bf1f-f0006e04485e', NULL, 'Đặng Quốc Hưng', 5, 'Giặt 14kg cho cả chăn ga, không phải ra tiệm nữa.', NOW() - INTERVAL '30 days'),
  ('6f7fe184-5f0c-5cf0-a062-fb477cda4e43', NULL, '1ed0d41d-a9f3-57d0-bf1f-f0006e04485e', NULL, 'Cao Bá Lộc', 4, 'Rất êm, gần như không nghe thấy. Chu trình thì khá dài.', NOW() - INTERVAL '22 days'),
  ('e83b0b06-1610-5828-961b-d6e1287eb697', NULL, '1ed0d41d-a9f3-57d0-bf1f-f0006e04485e', NULL, 'Nguyễn Thị Kim Oanh', 5, 'Giặt sạch, quần áo trắng sáng hơn máy cũ.', NOW() - INTERVAL '14 days'),
  ('bd03fbe4-359b-5f5e-b1cd-b42f6f30a298', NULL, '1ed0d41d-a9f3-57d0-bf1f-f0006e04485e', NULL, 'Kiều Diễm Hằng', 3, 'Một lần giặt mất gần 2 tiếng, hơi lâu nếu cần gấp.', NOW() - INTERVAL '5 days');
