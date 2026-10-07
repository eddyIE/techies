# Seed IDs

Single source of truth for the fixed UUIDs shared by `catalog-service` and
`inventory-service`. Both seed migrations are generated from this list, so stock always
attaches to a product that exists. **Do not edit either migration by hand.**

## Categories

| Slug | Name | UUID |
|---|---|---|
| `dien-thoai` | Điện thoại | `621a9347-0b52-58d5-ba30-9c524079231d` |
| `laptop` | Laptop | `1cf6c2ff-e4cd-50c1-bcb8-39c22a711b9b` |
| `tablet` | Máy tính bảng | `155c3f45-7376-54b8-8fe4-9859afd0cd8e` |
| `tai-nghe` | Tai nghe | `3a8e7aee-9455-52a3-be9a-3790e375821a` |
| `dong-ho-thong-minh` | Đồng hồ thông minh | `5e8b4230-0d61-5f2b-a468-5499e8e7331b` |
| `phu-kien` | Phụ kiện | `cec6d4cc-9d78-55d8-aaf7-35d3bcf87a9f` |
| `demo` | Demo | `4ef9d6be-fda4-514d-871f-3cb98fd5762b` |

## Products

| Slug | Name | Category | Price (VND) | Active | Seed stock |
|---|---|---|---:|---|---:|
| `iphone-15-pro-max-256gb` | iPhone 15 Pro Max 256GB | `dien-thoai` | 31,990,000 | yes | 120 |
| `iphone-15-128gb` | iPhone 15 128GB | `dien-thoai` | 22,990,000 | yes | 90 |
| `samsung-galaxy-s24-ultra` | Samsung Galaxy S24 Ultra 256GB | `dien-thoai` | 29,990,000 | yes | 75 |
| `samsung-galaxy-a55` | Samsung Galaxy A55 5G | `dien-thoai` | 9,490,000 | yes | 200 |
| `xiaomi-14-ultra` | Xiaomi 14 Ultra | `dien-thoai` | 24,990,000 | yes | 1 |
| `oppo-reno11-f` | OPPO Reno11 F 5G | `dien-thoai` | 8,490,000 | yes | 150 |
| `vivo-v30e` | vivo V30e 5G | `dien-thoai` | 8,990,000 | yes | 60 |
| `nothing-phone-2a` | Nothing Phone (2a) | `dien-thoai` | 9,990,000 | no | 40 |
| `macbook-air-m3-13` | MacBook Air M3 13 inch 8GB/256GB | `laptop` | 27,990,000 | yes | 55 |
| `macbook-pro-m3-14` | MacBook Pro M3 14 inch 16GB/512GB | `laptop` | 45,990,000 | yes | 30 |
| `dell-xps-13-9340` | Dell XPS 13 9340 Core Ultra 7 | `laptop` | 38,990,000 | yes | 25 |
| `asus-zenbook-14-oled` | ASUS Zenbook 14 OLED | `laptop` | 25,990,000 | yes | 70 |
| `lenovo-thinkpad-x1-carbon` | Lenovo ThinkPad X1 Carbon Gen 12 | `laptop` | 42,990,000 | yes | 18 |
| `hp-pavilion-15` | HP Pavilion 15 Core i5 | `laptop` | 16,990,000 | yes | 110 |
| `acer-nitro-v15` | Acer Nitro V 15 RTX 4050 | `laptop` | 21,990,000 | yes | 1 |
| `msi-modern-14` | MSI Modern 14 C13M | `laptop` | 13,990,000 | yes | 0 |
| `ipad-pro-m4-11` | iPad Pro M4 11 inch WiFi 256GB | `tablet` | 28,990,000 | yes | 40 |
| `ipad-air-m2-11` | iPad Air M2 11 inch WiFi 128GB | `tablet` | 16,990,000 | yes | 65 |
| `ipad-gen-10` | iPad Gen 10 WiFi 64GB | `tablet` | 10,490,000 | yes | 130 |
| `samsung-tab-s9-fe` | Samsung Galaxy Tab S9 FE | `tablet` | 11,990,000 | yes | 80 |
| `xiaomi-pad-6` | Xiaomi Pad 6 128GB | `tablet` | 7,990,000 | yes | 95 |
| `lenovo-tab-p12` | Lenovo Tab P12 | `tablet` | 8,990,000 | yes | 0 |
| `airpods-pro-2-usbc` | AirPods Pro 2 USB-C | `tai-nghe` | 5,690,000 | yes | 160 |
| `airpods-4` | AirPods 4 | `tai-nghe` | 3,390,000 | yes | 140 |
| `sony-wh-1000xm5` | Sony WH-1000XM5 | `tai-nghe` | 7,990,000 | yes | 45 |
| `sony-wf-1000xm5` | Sony WF-1000XM5 | `tai-nghe` | 5,990,000 | yes | 50 |
| `bose-quietcomfort-ultra` | Bose QuietComfort Ultra | `tai-nghe` | 8,990,000 | yes | 22 |
| `jbl-tune-770nc` | JBL Tune 770NC | `tai-nghe` | 2,290,000 | yes | 180 |
| `soundpeats-air4-pro` | SoundPEATS Air4 Pro | `tai-nghe` | 1,490,000 | yes | 200 |
| `marshall-major-v` | Marshall Major V | `tai-nghe` | 3,990,000 | yes | 1 |
| `apple-watch-series-9-45` | Apple Watch Series 9 45mm GPS | `dong-ho-thong-minh` | 10,990,000 | yes | 60 |
| `apple-watch-se-2-44` | Apple Watch SE 2 44mm GPS | `dong-ho-thong-minh` | 6,490,000 | yes | 85 |
| `samsung-watch6-classic` | Samsung Galaxy Watch6 Classic 47mm | `dong-ho-thong-minh` | 8,490,000 | yes | 35 |
| `garmin-forerunner-265` | Garmin Forerunner 265 | `dong-ho-thong-minh` | 11,990,000 | yes | 20 |
| `xiaomi-watch-s3` | Xiaomi Watch S3 | `dong-ho-thong-minh` | 2,990,000 | yes | 120 |
| `amazfit-gtr-4` | Amazfit GTR 4 | `dong-ho-thong-minh` | 4,290,000 | no | 25 |
| `anker-powerbank-20000` | Anker PowerCore 20.000mAh 22.5W | `phu-kien` | 990,000 | yes | 200 |
| `anker-sac-nhanh-65w` | Anker Sạc nhanh GaN 65W | `phu-kien` | 790,000 | yes | 175 |
| `ugreen-cap-usbc-2m` | UGREEN Cáp USB-C to USB-C 2m 100W | `phu-kien` | 290,000 | yes | 200 |
| `baseus-gia-do-dien-thoai` | Baseus Giá đỡ điện thoại ô tô | `phu-kien` | 350,000 | yes | 190 |
| `logitech-mx-master-3s` | Logitech MX Master 3S | `phu-kien` | 2,490,000 | yes | 55 |
| `keychron-k2-pro` | Keychron K2 Pro Wireless | `phu-kien` | 2,890,000 | yes | 1 |

## Demo fixtures

Products chosen to make each branch reproducible:

**Exactly 1 unit — concurrency / oversell demo:**

- `xiaomi-14-ultra` — Xiaomi 14 Ultra — `46be6c64-3e42-5c1d-b3d9-bfaa69128c81`
- `acer-nitro-v15` — Acer Nitro V 15 RTX 4050 — `cd11b47f-fba0-5d05-b842-f2d586ec167b`
- `marshall-major-v` — Marshall Major V — `99943638-b747-5ada-b10a-bed9bf25563c`
- `keychron-k2-pro` — Keychron K2 Pro Wireless — `108f437e-8b69-5d7c-b6eb-42d16c3b341a`

**0 units — `OUT_OF_STOCK` checkout branch:**

- `msi-modern-14` — MSI Modern 14 C13M — `b9606e9b-ec51-5d2a-b26e-fd1466cb8bf8`
- `lenovo-tab-p12` — Lenovo Tab P12 — `efe6ca96-5b39-5521-aa31-f30f6865ade6`

**Inactive — 404 on detail, flagged in the internal batch endpoint:**

- `nothing-phone-2a` — Nothing Phone (2a) — `01f18f88-0728-59d2-805c-29640538c4aa`
- `amazfit-gtr-4` — Amazfit GTR 4 — `35bb84e1-87b2-5a23-997e-a117dc87d48f`

## Crawled additions (catalog `V5`)

Added from cellphones.com.vn by `tools/crawler/` (internal, gitignored). Nothing here
replaces an existing row, so no order already placed can be invalidated.

The original UUIDs above were generated with a namespace that was never recorded, so
they stay as literals. Everything below is `uuid5` under
`uuid5(NAMESPACE_URL, "https://techies.vn/seed")` with the key `category:<slug>` or
`product:<slug>`, which makes regenerating the migration reproducible.

### Categories

| Slug | Name | UUID |
|---|---|---|
| `tivi` | Tivi | `13df1ea8-f30d-553f-a03c-7af6c0e0d2f4` |
| `may-tinh-de-ban` | Máy tính để bàn | `b300e933-d9b2-5527-8dae-ffd8abd6cd0f` |
| `am-thanh` | Âm thanh | `b3c7ffd4-af74-5471-8d98-eb1865f5241c` |
| `tu-lanh` | Tủ lạnh | `b1f38776-76d4-548b-81fa-f7f5bcd586f4` |
| `may-giat` | Máy giặt | `2ac73eb3-4007-5de9-a602-5390c65018f0` |

### Products

| Slug | Name | Category | Price (VND) | Specs |
|---|---|---|---:|---:|
| `smart-tivi-samsung-neo-qled-75qn80f-4k-75-inch-2025` | Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F) | `tivi` | 29,990,000 | 10 |
| `gia-treo-tivi-north-bayou-c3-fg` | GIÁ TREO TIVI 32 -75 INCH (C3-FG) | `tivi` | 350,000 | 4 |
| `pc-cps-gaming-g1-amd` | PC CPS Gaming G1 - AMD | `may-tinh-de-ban` | 10,530,000 | 14 |
| `pc-cps-core-work-p144f650` | PC CPS Core Work P144F650 i5-14400F / RX 6500 | `may-tinh-de-ban` | 19,930,000 | 7 |
| `tai-nghe-bluetooth-baseus-bowie-m2` | Tai nghe Bluetooth True Wireless Baseus Bowie M2 | `am-thanh` | 750,000 | 5 |
| `microphone-thu-am-boya-mic-2-1tx-1rx` | Microphone thu âm không dây Boya MIC 2 ( 1TX + 1RX ) | `am-thanh` | 2,790,000 | 9 |
| `tu-lanh-lg-inverter-side-by-side-gr-b256bl-519-lit` | LG Side By Side 519 lít 2023 (GR-B256BL) | `tu-lanh` | 13,490,000 | 10 |
| `tu-lanh-panasonic-inverter-nr-tv341vgmv-306-lit` | Panasonic 306 lít 2021 (NR-TV341VGMV) | `tu-lanh` | 10,590,000 | 11 |
| `may-giat-samsung-bespoke-14kg-inverter-ww14bb944dgbsv` | Samsung cửa ngang Bespoke 14kg 2024 (WW14BB944DGBSV) | `may-giat` | 13,490,000 | 9 |
| `may-giat-say-hitachi-inverter-10-5-bd-d1054hvos` | sấy Hitachi cửa ngang giặt 10.5kg - sấy 7kg 2023 (BD-D1054HVOS) | `may-giat` | 9,990,000 | 9 |
| `iphone-16-pro-max` | iPhone 16 Pro Max 256GB | `dien-thoai` | 30,990,000 | 16 |
| `dien-thoai-xiaomi-15t-pro-5g` | Xiaomi 15T Pro 5G 12GB 512GB | `dien-thoai` | 15,990,000 | 11 |
| `laptop-acer-aspire-lite-gen-2-al14-52m-32kv` | Acer Aspire Lite Gen 2 AL14-52M-32KV | `laptop` | 13,490,000 | 12 |
| `laptop-lenovo-ideapad-slim-5-15iru9-83d00003vn` | Lenovo IdeaPad Slim 5 15IRU9 83D00003VN | `laptop` | 17,990,000 | 11 |
| `may-doc-sach-kindle-new-2024-gen-11-16gb-khong-quang-cao` | Máy đọc sách New Kindle Gen 11 2024 16GB (Bản không quảng cáo) | `tablet` | 4,090,000 | 4 |
| `may-doc-sach-kindle-paperwhite-6-16gb-khong-quang-cao` | Máy đọc sách Kindle Paperwhite 6 16GB (Bản không quảng cáo) | `tablet` | 5,490,000 | 4 |
| `tai-nghe-bluetooth-tws-philips-tat3020` | Bluetooth True Wireless Philips TAT3020 | `tai-nghe` | 620,000 | 6 |
| `tai-nghe-khong-day-soundpeats-air-5-pro-plus` | Bluetooth True Wireless SoundPEATS Air 5 Pro+ | `tai-nghe` | 2,090,000 | 8 |
| `apple-watch-ultra-2-2024-49mm-4g-vien-titan-day-alpine-size-l` | Apple Watch Ultra 2 2024 49mm 4G Viền Titan Dây Alpine Size L | Chính hãng VN/A | `dong-ho-thong-minh` | 16,990,000 | 9 |
| `hub-usb-3-0-orico-twu3-4a-bk-3-in-1` | Hub USB 3.0 Orico TWU3-4A-BK 3 in 1 | `phu-kien` | 239,000 | 6 |

## Demo products for the tier ladder

Priced so a fresh account reaches each tier in one order, in this sequence. Catalog `V6`,
inventory `V4`. They sit in the `demo` category, sorted last, so the app can keep them out of
the real catalogue.

| Slug | Name | Price (VND) | Lifetime after | Reaches | Seed stock |
|---|---|---:|---:|---|---:|
| `demo-tier-a` | Demo A - 10 triệu | 10,000,000 | 10,000 | tier 1 | 999 |
| `demo-tier-b` | Demo B - 20 triệu | 20,000,000 | 30,000 | tier 2 | 999 |
| `demo-tier-c` | Demo C - 30 triệu | 30,000,000 | 60,000 | tier 3 | 999 |

| Slug | UUID |
|---|---|
| `demo-tier-a` | `9c57e463-c2ec-560b-b0ca-c84f6a7a56e7` |
| `demo-tier-b` | `0c00e340-1aee-5a44-90a5-1b734973daad` |
| `demo-tier-c` | `a55d0844-ed95-5ca5-b69b-374bcedf9e0e` |

## Loyalty gifts

Seeded by loyalty `V1`, in the `loyalty` schema. The catalogue spans the ladder so every
branch of `claim` is reachable without editing data (SPEC-loyalty.md, Seed Data).

| Name | Points | Min tier | Stock | Demonstrates |
|---|---:|---:|---:|---|
| Ốp lưng silicon | 500 | 0 | 50 | a claim from a standing start |
| Cáp sạc USB-C 1m | 800 | 0 | 40 | a second tier 0 claim |
| Tai nghe có dây | 2,000 | 1 | 25 | the tier 1 reward |
| Củ sạc nhanh 25W | 5,000 | 2 | 15 | the tier 2 reward |
| Tai nghe Bluetooth | 12,000 | 3 | 10 | the tier 3 reward, and `TIER_TOO_LOW` below it |
| Pin sạc dự phòng 10.000mAh | 1,500 | 0 | 0 | `GIFT_OUT_OF_STOCK` |
| Đồng hồ thông minh | 999,999 | 0 | 5 | `INSUFFICIENT_POINTS` at every tier |

| Name | UUID |
|---|---|
| Ốp lưng silicon | `ce2eb2ca-a432-5d29-9755-1f4f1abf2a14` |
| Cáp sạc USB-C 1m | `fa31e0b6-78d5-5739-a025-965512251dd0` |
| Tai nghe có dây | `1d61e4c8-962b-55bd-b0c9-f6c1045571ab` |
| Củ sạc nhanh 25W | `f0fb47a7-58e4-576e-9470-3b91dc212271` |
| Tai nghe Bluetooth | `5a895aa7-839d-5f07-9729-7f3a68806ac1` |
| Pin sạc dự phòng 10.000mAh | `e20b380a-f62c-56eb-9ba4-f7cbf404ee5c` |
| Đồng hồ thông minh | `378bdc0f-8cea-57b3-a646-86b07ac39296` |
