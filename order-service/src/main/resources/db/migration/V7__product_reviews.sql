-- Product reviews, written by customers who bought the product.
--
-- One review per ORDER LINE, not per product: a customer who buys the same thing twice
-- reviews it twice. The alternative leaves the second order permanently un-reviewable,
-- which contradicts the app showing 'not reviewed yet' against an order.
--
-- Reviews live here rather than in catalog-service because everything they need is
-- already local: whether the caller bought the item, and whether an order still has
-- lines to review. Putting them in catalog would force order-service to call catalog on
-- every order list just to render that badge. The trade is that a product's rating is
-- not part of the catalog payload; the app reads it from GET /products/{id}/reviews,
-- which the gateway routes here.
--
-- author_name is a snapshot. A review should keep showing the name as it was written,
-- and it lets the seeded demo rows below exist without inventing user accounts.

CREATE TABLE product_reviews (
    id            UUID         PRIMARY KEY,
    -- NULL only for the seeded demo rows below, which belong to no real purchase.
    -- UNIQUE still enforces one review per line, since Postgres allows repeated NULLs.
    order_item_id UUID         UNIQUE REFERENCES order_items (id) ON DELETE CASCADE,
    product_id    UUID         NOT NULL,
    user_id       UUID,
    author_name   VARCHAR(120) NOT NULL,
    rating        SMALLINT     NOT NULL,
    comment       VARCHAR(1000),
    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_product_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);

-- The product listing is the hot path: newest first, per product.
CREATE INDEX ix_product_reviews_product ON product_reviews (product_id, created_at DESC);

-- Demo reviews. Deliberately uneven -- a catalogue where everything scores five stars
-- reads as fabricated, so there are threes and fours here too.
INSERT INTO product_reviews (id, order_item_id, product_id, user_id, author_name, rating, comment, created_at) VALUES
  ('d7637936-ba03-563c-b2c3-b3b3f0840976', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Nguyễn Minh Anh', 5, 'Máy đẹp, pin dùng cả ngày thoải mái. Giao hàng nhanh, đóng gói kỹ.', NOW() - INTERVAL '12 days'),
  ('2aae0964-7370-5db3-bcae-6497d55cb2b5', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Trần Quốc Bảo', 5, 'Camera chụp đêm rất tốt, nâng cấp từ bản 13 Pro thấy đáng tiền.', NOW() - INTERVAL '8 days'),
  ('ec017954-841d-5a31-8395-acd8738b0bfa', NULL, '1f8d1d18-6b82-542e-bcca-e7485fe03e0d', NULL, 'Lê Thu Hà', 4, 'Sản phẩm tốt nhưng máy hơi nặng, dùng một tay chưa quen.', NOW() - INTERVAL '3 days'),
  ('258b4f54-a56d-5234-bcba-df653de44c28', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Phạm Văn Dũng', 5, 'Bút S Pen tiện cho công việc, màn hình ngoài trời nhìn rất rõ.', NOW() - INTERVAL '20 days'),
  ('2e76c112-0a79-542a-b6b4-5d72450b8460', NULL, '34354145-85e1-5538-b6e5-a46656eebaf3', NULL, 'Hoàng Thị Mai', 4, 'Hiệu năng mạnh, chỉ tiếc là sạc hơi lâu so với kỳ vọng.', NOW() - INTERVAL '6 days'),
  ('a723bfdb-9999-50fc-bc4c-2b234b79bead', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Đặng Hoàng Nam', 5, 'Dựng video 4K mượt, quạt gần như không kêu. Rất hài lòng.', NOW() - INTERVAL '25 days'),
  ('a0c431f8-6bfa-50c4-bc23-b33fdafc2fd0', NULL, '287496fe-7be5-597e-926f-a3594e633cbf', NULL, 'Vũ Khánh Linh', 5, 'Màn hình tuyệt vời, pin dùng gần hai ngày với việc văn phòng.', NOW() - INTERVAL '14 days'),
  ('ae5ec303-1280-5f02-addc-6328513c9170', NULL, '9db2c05c-f0ef-5d7c-b075-377083159bf2', NULL, 'Bùi Tiến Đạt', 4, 'Mỏng và nhẹ, vẽ với Apple Pencil rất nhạy. Giá hơi cao.', NOW() - INTERVAL '9 days'),
  ('2c1637bf-4f66-5fdb-86cc-d087e22239fa', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Ngô Thanh Tú', 5, 'Chống ồn xuất sắc, đi máy bay đeo cả chuyến không mệt tai.', NOW() - INTERVAL '30 days'),
  ('788ecd97-9c4d-5c24-af6f-b432515b5a8f', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Trịnh Gia Huy', 4, 'Âm thanh hay, nhưng hộp sạc dễ xước sau một tháng dùng.', NOW() - INTERVAL '11 days'),
  ('9c475dab-63de-50da-929f-f2f1f452e3ea', NULL, 'c29f5a79-bcd8-5ef8-92fe-6588e1238388', NULL, 'Lý Phương Thảo', 5, 'Kết nối nhanh, đổi giữa điện thoại và laptop rất tiện.', NOW() - INTERVAL '2 days'),
  ('bb7edc3c-86eb-5d84-a387-3093b654fa66', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Cao Minh Khôi', 5, 'Dùng với iPhone quá tiện, chống ồn tốt hơn bản cũ rõ rệt.', NOW() - INTERVAL '18 days'),
  ('f78473a3-d4f4-5dfb-b568-05cd089150b2', NULL, '04ce81f6-5b66-5a7d-893f-8b416be1851d', NULL, 'Đỗ Ngọc Ánh', 3, 'Âm thanh ổn nhưng tai mình đeo lâu bị đau, cần cân nhắc.', NOW() - INTERVAL '5 days'),
  ('69464cbd-1547-5693-8e38-6be952af908f', NULL, '8f2ab3b5-6a9a-5471-9172-b721e82fcc92', NULL, 'Hà Anh Tuấn', 4, 'Theo dõi sức khoẻ chính xác, pin vẫn phải sạc mỗi ngày.', NOW() - INTERVAL '16 days'),
  ('1fa848a4-bf4c-5362-959a-3c1dec7f9344', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Nguyễn Hải Yến', 4, 'Giá này mà có chống ồn thì quá tốt, bass mạnh.', NOW() - INTERVAL '7 days'),
  ('408c9aef-f528-5825-9f7e-3472f8fd5d33', NULL, '513f03f9-8a1c-5813-9feb-2cae892e82a9', NULL, 'Phan Đức Thắng', 3, 'Âm ổn trong tầm giá, nhựa vỏ cảm giác hơi mỏng.', NOW() - INTERVAL '4 days'),
  ('49d1e40c-d88f-505f-b0c1-fa431a3944df', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Trương Bảo Châu', 5, 'Hình ảnh sắc nét, xem phim như ở rạp. Lắp đặt tận nhà nhanh.', NOW() - INTERVAL '10 days'),
  ('c72e103e-e9c1-5031-a4e6-b39d5eb8c273', NULL, '759d9034-b058-5375-aa35-cadf437332c3', NULL, 'Lâm Quang Vinh', 5, 'Màu rất đẹp, chơi game 120Hz mượt. Rất đáng giá.', NOW() - INTERVAL '1 days'),
  ('f428cd09-897a-5910-9946-7d59281697b3', NULL, 'e2a02554-c7ac-5509-be71-d407c0b0c902', NULL, 'Mai Xuân Trường', 4, 'Máy chạy êm, chơi game tầm trung tốt. Nên nâng thêm RAM.', NOW() - INTERVAL '13 days');
