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
