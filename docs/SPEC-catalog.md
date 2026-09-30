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
them, realistic Vietnamese names and VND prices. Product UUIDs are **fixed literals**, not
generated — `inventory` seeds stock against these same IDs, and the demo script references them.

Images arrived in three passes. `V2` used `picsum.photos`, which returns an unrelated
photograph per seed, so a phone could be illustrated with a mountain. `V3` replaced those with
generated `placehold.co` images rendering each product's own name in a colour per category, so
the picture always matched the product and no binary assets entered the repo. `V4` then
hotlinks a real photograph from the manufacturer or a retailer that sells the product, for the
18 of 42 products where the exact model could be confirmed.

Confirming the model is the hard part and the reason for the other 24. The catalogue is
2023-24 hardware, so shops and manufacturers have since moved to later generations: a search
for the AirPods Pro 2 page now lands on AirPods Pro 3, and the laptop listings lead with M5
MacBooks. `V4` therefore accepts a URL only when the source page's own product name carries
the exact model and the URL returns real image bytes; anything looser silently ships the wrong
generation. The products that fail that test keep their `V3` placeholder, which at least names
the product correctly.

These images are hotlinked and remain their publishers' copyright, used here for a
non-published university demo. They are outside our control, so a URL can rotate or start
refusing hotlinks, and that image 404s until `V4` is regenerated.

Shared fixed IDs live in `docs/SEED-IDS.md` so catalog and inventory migrations cannot drift.

`V5` enriches the catalogue from cellphones.com.vn: five new top-level categories and twenty
products, crawled by `tools/crawler/` (internal, gitignored -- it scrapes a third party and
only exists to generate the committed SQL). Nothing existing is replaced, so an order already
placed cannot be invalidated.

## Specifications

`product_specs` holds the "Thông số kỹ thuật" table as ordered name/value rows, nested into
`GET /products/{id}` as `specifications`. Rows rather than JSON, for the same reason as
`product_images`: the order is part of the data, and filtering on a spec later stays an
ordinary query.

Two sources are needed, because neither is sufficient. The GraphQL API exposes
`general.attributes`, but as ~95 raw Magento codes padded with filler (`ads_base_image:
no_selection`); the rendered product page carries the curated, human-labelled subset a
customer actually reads. So products and prices come from the API and specifications from
the page.

Only **one** of the original 42 products could be given specifications. The rest are 2023-24
models the source no longer sells -- its laptop listing carries M4 and M5 MacBooks and no M3,
and it stocks no AirPods at all. The matcher fails closed: it requires every model-identifying
token, because a near-miss pairs a Baseus car mount with Baseus earbuds, and a wrong
specification on a real product page is worse than none. Those products return an empty list
and the app hides the section.

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
