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
