# Task List

Ordered by dependency. Each task: ≤5 files where practical, explicit acceptance, explicit
verification, one commit. Plan: `tasks/plan.md`.

---

## A. Foundation

- [x] **A1. Parent POM + module skeleton, versions proven**
  - Acceptance: `git init` done; parent `pom.xml` pins Java 21, Spring Boot and Spring Cloud to a
    pairing that resolves; all 7 modules declared; `.gitignore` and `.env.example` committed;
    `mvnw` wrapper generated.
  - Verify: `./mvnw clean install` exits 0. Record the exact pinned versions in `docs/SPEC.md`.
  - Files: `pom.xml`, `.gitignore`, `.env.example`, `mvnw`, `docs/SPEC.md`

- [x] **A2. `common` module — error envelope**
  - Acceptance: `ApiError` record matching `SPEC.md § Error Model`; `ErrorCode` enum with every
    code named across the specs; `GlobalExceptionHandler` mapping validation, not-found,
    forbidden and conflict; `UserPrincipal`; shared inter-service DTOs.
  - Verify: unit test asserts the serialized JSON shape field-for-field.
  - Files: `common/` (~6 files)

- [x] **A3. Docker Compose — Postgres + schema bootstrap**
  - Acceptance: Postgres 16 service; init SQL creating schemas `identity`, `catalog`,
    `inventory`, `orders`; named volume; `.env`-driven credentials.
  - Verify: `docker compose up -d postgres` then `\dn` lists all 4 schemas.
  - Files: `docker-compose.yml`, `docker/postgres/init.sql`

- [x] **A4. `discovery-server` — Eureka**
  - Acceptance: Eureka server on 8761, `register-with-eureka: false`, Dockerfile.
  - Verify: `docker compose up -d discovery-server`; `curl :8761/eureka/apps` returns 200.
  - Files: `discovery-server/` (~4 files)

> **Checkpoint A:** `./mvnw clean install` green, Eureka up, 4 schemas present.

---

## B. Leaf services

### identity

- [x] **B1.1. Entities + migrations**
  - Acceptance: `User`, `Address` entities; `V1__init.sql`; unique index on `lower(email)`;
    Flyway configured against schema `identity`.
  - Verify: `@DataJpaTest` + Testcontainers; migration applies clean.
  - Files: `identity-service/` domain + repository + `V1__init.sql`

- [x] **B1.2. Auth endpoints + JWT**
  - Acceptance: `register`, `login`, `check-email`, `reset-password` per `SPEC-identity.md`;
    BCrypt hashing; HS256 JWT, **30-day TTL**, secret from env. No `/logout`.
  - Verify: register → login → decode token and assert `sub`/`exp`; duplicate email → 409;
    wrong password → 401; `reset-password` with only email+newPassword → 204 and new password logs in.
  - Files: `api/AuthController`, `service/AuthService`, `service/JwtService`, DTOs, tests

- [x] **B1.3. User + address endpoints**
  - Acceptance: `/users/me`, `/users/{id}` (403 unless self), `/users/me/password`, address CRUD,
    single-default invariant, `/internal/addresses/{id}?userId=`.
  - Verify: slice tests for 403 paths; default-address promotion on delete; `password_hash`
    absent from every response.
  - Files: `api/UserController`, `api/AddressController`, `service/AddressService`, tests

### catalog

- [x] **B2.1. Entities + migrations + SEED-IDS**
  - Acceptance: `Category`, `Product`, `ProductImage`; `V1__init.sql`; GIN index for search;
    `docs/SEED-IDS.md` with fixed UUID literals as the single source of truth.
  - Verify: `@DataJpaTest`; migration clean; SEED-IDS parses as valid UUIDs.
  - Files: `catalog-service/` domain + `V1__init.sql`, `docs/SEED-IDS.md`

- [x] **B2.2. Seed data**
  - Acceptance: `V2__seed_catalog.sql` — ≥6 categories, ≥40 products, Vietnamese names, VND
    prices, picsum image URLs, ≥2 inactive products for the 404 test. IDs from SEED-IDS.md.
  - Verify: after migration, counts assert ≥6 / ≥40; every product id appears in SEED-IDS.md.
  - Files: `V2__seed_catalog.sql`

- [x] **B2.3. Catalog endpoints**
  - Acceptance: `/categories`, `/products` (keyword, categoryId, price range, page, size≤100,
    4 sorts), `/products/{id}`, `/internal/products/batch`.
  - Verify: accent-insensitive keyword match; `size=500` clamps to 100; inactive → 404 on detail
    but returned flagged by batch; batch of 40 ids issues one query.
  - Files: `api/`, `service/CatalogService`, `repository/ProductRepository`, tests

### inventory

- [x] **B3.1. Entities + migrations + seed**
  - Acceptance: `StockItem` with `@Version` and `CHECK (available >= 0)`; `StockMovement` +
    items with UNIQUE `(order_ref, type)`; `V2__seed_stock.sql` from SEED-IDS.md including
    ≥3 products at exactly 1 unit and ≥2 at 0.
  - Verify: `@DataJpaTest`; CHECK constraint rejects a negative update; UNIQUE rejects a duplicate
    `(order_ref, DEDUCT)`.
  - Files: `inventory-service/` domain + `V1__init.sql` + `V2__seed_stock.sql`

- [x] **B3.2. deduct / restore / read + concurrency proof**
  - Acceptance: all three endpoints per `SPEC-inventory.md`; atomic conditional UPDATE;
    all-or-nothing multi-line; idempotent on `(order_ref, type)`; `restore` of an un-deducted
    ref → 409.
  - Verify: **10 parallel threads deducting the last unit → exactly 1 success, 9× 409,
    `available` = 0.** Plus deduct→restore round-trip returning the exact original value.
  - Files: `api/StockController`, `service/StockService`, `repository/`, tests

> **Checkpoint B:** all three register with Eureka; own tests green; oversell test passes.

---

## C. order

- [x] **C1. Cart entities + migrations**
  - Acceptance: `Cart` (UNIQUE user_id), `CartItem` (UNIQUE cart_id+product_id, qty 1–99);
    `V1__init.sql` on schema `orders`.
  - Verify: `@DataJpaTest`; duplicate (cart, product) rejected.
  - Files: `order-service/` domain + `V1__init.sql`

- [x] **C2. Feign clients**
  - Acceptance: clients for identity `/internal/addresses`, catalog `/internal/products/batch`,
    inventory `deduct`/`restore`/`GET stock`; connect 2s / read 5s; retry once on `deduct` only.
  - Verify: WireMock tests for success, 4xx, timeout; assert retry fires on deduct and nowhere else.
  - Files: `client/` (3 clients + config), tests

- [x] **C3. Cart endpoints**
  - Acceptance: `GET /cart`, `POST /cart/items`, `PUT /cart/items/{id}`, `DELETE /cart/items/{id}`;
    re-add increments; `quantity:0` deletes; enrichment from catalog + inventory; degrades to
    `available:null` when a downstream is down rather than failing the cart.
  - Verify: slice + integration; downstream-down case asserts 200 with null availability.
  - Files: `api/CartController`, `service/CartService`, DTOs, tests

- [x] **C4. Order entities + `saga_steps`**
  - Acceptance: `Order`, `OrderItem`, `SagaStep`; status + failure-code enums; `order_ref`
    sequence `ORD-yyyyMMdd-NNNN`; address and price snapshot columns.
  - Verify: `@DataJpaTest`; two orders same day get sequential refs.
  - Files: domain + `V2__orders.sql`

- [x] **C5. PaymentSimulator**
  - Acceptance: `COD` always approves; `MOCK_CARD` honours `simulatePayment` =
    `SUCCESS`|`DECLINED`|`TIMEOUT`; no external call, no card data.
  - Verify: unit tests for all four combinations; `TIMEOUT` is treated as declined.
  - Files: `service/payment/` (~3 files), tests

- [x] **C6. Checkout saga orchestrator** ← *the core deliverable*
  - Acceptance: 7 steps per `SPEC-order.md`; steps 1–3 create no order row; every step writes a
    `saga_steps` row; payment failure triggers `restore` and marks `COMPENSATED`; cart cleared
    only on success; returns 200 with a FAILED order on business failure.
  - Verify: integration tests for success, `OUT_OF_STOCK` (asserting **no payment step logged**),
    `PAYMENT_FAILED` (asserting **stock exactly restored** and cart intact), and inventory-down
    → `SERVICE_UNAVAILABLE` with no partial state.
  - Files: `service/CheckoutSagaOrchestrator`, `service/OrderService`, `api/CheckoutController`, tests

- [x] **C7. Order history + cancel**
  - Acceptance: `GET /orders` (paged, newest first, status filter), `GET /orders/{id}` (403 if
    not owner), `POST /orders/{id}/cancel` (only from `CONFIRMED`, restores stock, 409 otherwise).
  - Verify: cancel restores stock; cancelling a `FAILED` order → 409; price changed after
    ordering → order still shows the snapshot.
  - Files: `api/OrderController`, `service/OrderService`, tests

> **Checkpoint C:** both checkout branches correct against live services; compensation proven.

---

## D. gateway

- [x] **D1. Routes + Eureka discovery**
  - Acceptance: every route in `SPEC-gateway.md`; `StripPrefix=1`; `/internal/**` and
    `/stock/deduct|restore` unroutable; only 8080 published in compose.
  - Verify: public routes 200 without a token; `POST /api/stock/deduct` → 404; killing one
    service leaves others routable.
  - Files: `api-gateway/` config + `application.yml`

- [x] **D2. JWT filter + header-forgery defence**
  - Acceptance: validates HS256 + `exp`; injects `X-User-Id` / `X-User-Email`; **strips inbound
    `X-User-*` before injecting**; errors use the shared envelope.
  - Verify: no token → 401; expired → 401; **forged `X-User-Id` without a token never reaches a
    service**; forged header alongside a valid token is overwritten with the token's user id.
  - Files: `filter/JwtAuthenticationFilter`, `config/SecurityConfig`, tests

> **Checkpoint D:** auth correct at the edge; forgery test green.

---

## E. Integration and demo

- [x] **E1. Full compose wiring**
  - Acceptance: all 7 containers; healthchecks; `depends_on` ordering; only 8080 host-published;
    all services `UP` in Eureka within 90s on a clean machine.
  - Verify: `docker compose down -v && docker compose up --build` from scratch; poll Eureka.
  - Files: `docker-compose.yml`, per-service `Dockerfile`

- [x] **E2. The 7 non-negotiable test cases**
  - Acceptance: each case from `SPEC.md § Testing Strategy` exists as a named end-to-end test.
  - Verify: `./mvnw verify -Pintegration` green; ≥80% line coverage on `service/` packages.
  - Files: `order-service/src/test/.../integration/`

- [x] **E3. `docs/DEMO.md`**
  - Acceptance: copy-pasteable `curl` walkthrough — register, browse, cart, successful checkout,
    **declined checkout showing stock restored**, cancel, and the concurrency race. Names the
    exact low-stock product ids from SEED-IDS.md.
  - Verify: run every command against a fresh stack; outputs match what the doc claims.
  - Files: `docs/DEMO.md`

- [x] **E4. OpenAPI + README**
  - Acceptance: springdoc on all 5 services; root `README.md` with architecture diagram,
    run instructions, and links into `docs/`.
  - Verify: each `/swagger-ui.html` reachable and lists every endpoint in that module's spec.
  - Files: `README.md`, per-service springdoc config

> **Checkpoint E:** clean-machine `docker compose up --build` works; all 7 cases green.

---

## F. loyalty service

Spec: `docs/SPEC-loyalty.md`. A leaf: it calls nothing, so it builds before `order` needs it.

- [x] **F1. Module skeleton, schema bootstrap, compose entry**
  - Acceptance: `loyalty-service` declared in the parent POM; Spring Boot app on port 8086;
    Eureka client registers; `spring.flyway.schemas: loyalty` so **Flyway creates the schema
    itself** rather than depending on `docker/postgres/init.sql`, which only runs on a fresh
    volume and would otherwise force a full reseed; `loyalty` still added to `init.sql` for new
    volumes; compose service with an actuator healthcheck and no host-published port.
  - Verify: `docker compose up -d loyalty-service` reaches healthy on the **existing** volume
    without `--clean`; service shows `UP` in Eureka.
  - Files: `pom.xml`, `loyalty-service/` (~5 files), `docker/postgres/init.sql`, `docker-compose.yml`

- [x] **F2. `V1__init.sql` — five tables and the seed catalogue**
  - Acceptance: `points_ledger` (signed `points`, `ck_points_sign`, UNIQUE `(entry_type,
    reference)`), `tiers` (3 rows: 10000/30000/60000 at 10/30/50%), `gifts`,
    `gift_redemptions` (UNIQUE `(user_id, gift_id)`, UNIQUE `code`), `tier_vouchers`
    (UNIQUE `(user_id, tier)`). Seed gifts cover **all four** claim rejection branches per the
    spec: ≥2 at `min_tier` 0, ≥1 per tier 1/2/3, ≥1 at `stock` 0, ≥1 priced above any
    reachable balance.
  - Verify: migration applies clean; a test asserts each seeded branch exists by query.
  - Files: `loyalty-service/src/main/resources/db/migration/V1__init.sql`

- [x] **F3. Ledger, tier derivation, `POST /loyalty/points`**
  - Acceptance: balance `SUM(points)` and lifetime `SUM(points) WHERE points > 0`, both derived,
    nothing stored; `points = floor((subtotal - discount) / 1000)`; award idempotent on
    `(ORDER_EARN, orderRef)`; crossing one or more thresholds issues one voucher per rung
    crossed, in the same transaction, idempotent on `(user_id, tier)`.
  - Verify: unit tests for the earning, idempotency and tier criteria in SPEC-loyalty.md,
    including one order crossing tiers 1 and 2 together.
  - Files: `loyalty-service/src/main/java/.../loyalty/` (~6 files), test

- [x] **F4. Read endpoints: `/loyalty/me`, `/loyalty/gifts`, `/loyalty/vouchers`**
  - Acceptance: `me` returns `lifetimePoints`, `balance`, `tier` 0-3, `pointsToNextTier` and the
    `tiers` ladder; `gifts` returns per-user `eligible` and `alreadyClaimed`; tier is **never
    named**, only numbered.
  - Verify: unit tests; a fresh account returns tier 0, balance 0, no vouchers.
  - Files: `loyalty-service/src/main/java/.../api/` (~4 files), test

- [x] **F5. `POST /loyalty/gifts/{id}/claim` and `/loyalty/claimed-gifts`**
  - Acceptance: the five-step local transaction in spec order; `GIFT-XXXX-XXXX` codes, random and
    over an alphabet without `O`/`0`/`I`/`1`/`L`; `gift_name` and `points_spent` snapshotted; all
    four 409s distinct; redemption is terminal, with no status column and no expiry.
  - Verify: tests for each 409; lifetime unchanged after a claim; 10 parallel claims of a
    1-stock gift by 10 users leave exactly 1 winner and `stock` 0; two codes are not sequential.
  - Files: `loyalty-service/src/main/java/.../loyalty/` (~4 files), test

- [x] **F6. Voucher `consume` and `release`**
  - Acceptance: atomic consume before payment; idempotent on `(code, order_ref)`; `release`
    reverses it and returns 409 `NOTHING_TO_RELEASE` for a code never consumed; both endpoints
    internal only.
  - Verify: tests; two parallel consumes of one code leave exactly one winner.
  - Files: `loyalty-service/src/main/java/.../loyalty/` (~3 files), test

> **Checkpoint F:** loyalty is complete and green standing alone, with no caller.

## G. order to loyalty

- [ ] **G1. Award points when an order reaches `COMPLETED`**
  - Acceptance: `LoyaltyClient` Feign interface; `updateStatus` calls it **only** on the
    transition into `COMPLETED`; a loyalty outage logs and leaves the status change committed,
    because points must never block the lifecycle.
  - Verify: test asserts one award per transition and none on any other status change.
  - Files: `order-service/src/main/java/.../client/LoyaltyClient.java`, `OrderService`, test

- [ ] **G2. Vouchers in the checkout saga and on cancellation**
  - Acceptance: checkout resolves a submitted code against `coupons` locally first, then falls
    through to loyalty; consume is saga step 3, before the order row; `release` compensates on
    stock failure, payment failure **and user cancellation**, the third path mirroring how one
    `restore` already serves both failure and cancellation in `SPEC-inventory.md`; the discount
    is snapshotted onto the order like a coupon discount.
  - Verify: tests for all three release paths; a voucher survives a declined payment unconsumed.
  - Files: `order-service/src/main/java/.../service/` (~3 files), test

## H. AI review summary

- [ ] **H1. `POST /ai/review-summary` in ai-service**
  - Acceptance: reviews arrive in the request body so the service fetches nothing and keeps its
    no-schema property; returns `{pros, cons, verdict}` as short Vietnamese phrases; no tools and
    no web search; grounded strictly in the supplied text; a criticism several reviewers raise
    survives a high average; internal only, not gateway-routed.
  - Verify: the five acceptance criteria in SPEC-ai.md; no live API call in the unit tests.
  - Files: `ai-service/src/main/java/.../` (~4 files), test

- [ ] **H2. Cache and endpoint in order-service**
  - Acceptance: `product_review_summaries` keyed on `product_id` with the `review_count` it was
    generated from; `GET /products/{productId}/review-summary` returns the summary or `null`
    below three reviews; a stale count regenerates; an `ai-service` failure serves the previous
    summary when cached and `null` otherwise, **never** an error.
  - Verify: tests for null-below-three, regeneration on count change, and graceful degradation.
  - Files: `order-service/.../db/migration/V10__review_summaries.sql`, service, controller, test

## I. Wiring, seed data and docs

- [ ] **I1. Gateway route and demo products**
  - Acceptance: `/api/loyalty/**` routed to `loyalty-service` with JWT validation and
    `X-User-Id` injection; `/loyalty/points`, `/loyalty/vouchers/*/consume` and `*/release`
    **not** routed, and neither is `/ai/review-summary`; catalog seeds the three demo products
    at 10M/20M/30M in their own category; inventory stocks them at 999.
  - Verify: `GET /api/loyalty/me` with a token returns 200; `POST /api/loyalty/points` returns
    404; buying Demo A then completing the order lands the account on tier 1.
  - Files: `api-gateway/src/main/resources/application.yml`, catalog `V6__demo_products.sql`,
    inventory `V4__demo_stock.sql`, `docs/SEED-IDS.md`

- [ ] **I2. Correct the stale Gemini quota claims**
  - Acceptance: the free tier's "20 requests per day" is replaced everywhere by the paid-tier
    reality: the per-project RPM/TPM/RPD are read from AI Studio rather than hardcoded, and
    grounding with Google Search is billed on its **own monthly allowance** shared across the
    Gemini 3.x family, not against the model's daily requests. Fixed in all five places that
    repeat it, keeping `docs/API.md` byte-identical with its generator.
  - Verify: `grep -rn '20 per day\|20 requests per day' docs/ scripts/` returns nothing.
  - Files: `docs/SPEC-ai.md`, `docs/API.md`, `scripts/generate-api-docs.py`,
    `scripts/generate-postman.py`, `docs/postman/README.md`
  - Note: ask the user for their project's actual RPM/RPD if a concrete figure is wanted.

- [ ] **I3. Regenerate the API docs and extend the demo**
  - Acceptance: `docs/API.md` and the Postman collection carry every new endpoint; `docs/DEMO.md`
    gains the tier walk (three orders, hand-pushed to `COMPLETED`) and a gift claim showing the
    code and the re-claim refusal; `docs/EXTENSIONS.md` records delivered gifts and gift
    collection tracking as deliberate non-goals with their cost.
  - Verify: run every new `DEMO.md` command against the live stack; outputs match the doc.
  - Files: `docs/API.md`, `docs/postman/`, `docs/DEMO.md`, `docs/EXTENSIONS.md`

> **Checkpoint I:** a fresh account can be walked from tier 0 to tier 3 and claim a gift, end to
> end through the gateway, using only commands copied from `docs/DEMO.md`.
