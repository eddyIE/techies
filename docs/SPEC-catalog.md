# Spec: catalog

Module id `catalog` · port 8082 · schema `catalog` · depends on: nothing
Covers sheet rows 13 (Categories), 14–16 (Product, Search, Detail).

## Objective

Serve the browsable catalog: categories, paginated product lists, keyword search, and product
detail. Read-only to the outside world — there is no admin site, so all data arrives via
Flyway seed migrations.

Catalog deliberately does **not** own stock. Stock lives in `inventory`; see CAPABILITY-MAP.md
for why that split exists.

## Data Model

**categories** — id (uuid), name, slug (UNIQUE), image_url, display_order.

**products**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| category_id | uuid FK → categories | indexed |
| name | varchar(200) | |
| slug | varchar(220) | UNIQUE |
| description | text | |
| price | numeric(19,2) | > 0 |
| thumbnail_url | varchar(500) | |
| active | boolean | default true |
| created_at | timestamptz | |

**product_images** — id, product_id FK, url, display_order.

Full-text search index: `GIN (to_tsvector('simple', name || ' ' || description))`.

## Endpoints

All public — browsing requires no JWT.

| Method | Path | Notes |
|---|---|---|
| GET | `/categories` | 200, ordered by `display_order` |
| GET | `/products` | Paginated list + search |
| GET | `/products/{id}` | 200 detail with images; 404 if missing or inactive |

`GET /products` query parameters:

| Param | Type | Default | Notes |
|---|---|---|---|
| keyword | string | — | Sheet row 15. Matches name + description, case/accent-insensitive |
| categoryId | uuid | — | |
| minPrice / maxPrice | decimal | — | |
| page | int | 0 | |
| size | int | 20 | max 100 |
| sort | enum | `newest` | `newest`, `price_asc`, `price_desc`, `name_asc` |

Response is a page envelope: `{ content, page, size, totalElements, totalPages }`.

Internal (called by `order` at checkout):

| Method | Path | Purpose |
|---|---|---|
| POST | `/internal/products/batch` | Body `{productIds:[...]}` → id, name, price, thumbnail, active. One call per checkout, not N. |

## Seed Data

Delivered as `V2__seed_catalog.sql`. Minimum: **6 categories**, **40 products** spread across
them, realistic Vietnamese names and VND prices, `https://picsum.photos/seed/{slug}/600` as
image URLs so no binary assets enter the repo. Product UUIDs are **fixed literals**, not
generated — `inventory` seeds stock against these same IDs, and the demo script references them.

Shared fixed IDs live in `docs/SEED-IDS.md` so catalog and inventory migrations cannot drift.

## Rules

- Inactive products are excluded from list and search, and 404 on detail.
- `/internal/products/batch` *does* return inactive products, flagged — `order` needs to reject
  a checkout containing one, and needs to know why.
- Prices are VND, `numeric(19,2)`. No currency field; single-currency is an accepted limitation.
- No write endpoints. Adding one requires a spec change (SPEC.md → Boundaries → Ask first).

## Acceptance Criteria

- [ ] `GET /categories` returns the 6 seeded categories in `display_order`.
- [ ] `GET /products` defaults to page 0, size 20, and reports correct `totalElements`.
- [ ] `GET /products?keyword=...` matches accent-insensitively ("ao" matches "Áo").
- [ ] `size=500` is clamped to 100 rather than erroring.
- [ ] `sort=price_asc` orders correctly across the full seeded set.
- [ ] `GET /products/{inactiveId}` → 404, but the batch endpoint returns it with `active=false`.
- [ ] Batch endpoint with 40 ids answers in one query, verified by test.
