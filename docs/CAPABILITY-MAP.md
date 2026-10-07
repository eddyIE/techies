# Capability Map: E-Commerce Mobile Backend (Nhóm 3)

Source of requirements: `Java - BT Lớn.xlsx` (sheets: Chức năng, List màn hình, Order Flow).

## Modules

| Module id | Responsibility | Depends on |
|---|---|---|
| identity | Register, login, password reset, profile, addresses | — |
| catalog | Categories, product list, search, product detail | — |
| inventory | Stock levels, atomic deduct / compensating restore | — |
| order | Cart, checkout saga, mock payment, orders, cancel | identity, catalog, inventory, loyalty |
| gateway | Single entry point, JWT validation, routing | identity |
| ai | Product assistant chat, streamed over SSE | catalog, inventory |
| loyalty | Points ledger, tier ladder, reward vouchers, gift claim codes | — |

Infrastructure (not capability modules): `discovery-server` (Eureka), `common` (shared DTOs + error model), Postgres, Docker Compose. `ai` needs no schema and no database: it holds no state, and the Gemini API is the only external dependency any module has.

**Build order:** identity, catalog, inventory, loyalty (parallel) → order → gateway → ai

`ai` is last because it is additive. It was written long after the rest (commit `10390dc`) and
the one thing that depends on it, the review summary `order` asks it to write, degrades to a
cached or empty summary when it is unreachable. So the backend stays complete and demoable
without it. See SPEC-ai.md.

`loyalty` was added later still (2026-10-07) but builds early, because it is a leaf: it calls
nothing, so only `order` has to wait for it. See SPEC-loyalty.md.

## Dependency direction

```
gateway ──► (routes to all)
order ──► identity   (address snapshot)
      ──► catalog    (price + product snapshot)
      ──► inventory  (deduct / restore)
      ──► loyalty    (award points, consume / release voucher)
      ──► ai         (review summary text, best-effort)
ai    ──► catalog    (product detail + search)
      ──► inventory  (stock, for the model only)
```

No cycles. `identity`, `catalog`, `inventory` and `loyalty` know nothing about `order` or `ai`,
and `ai` reads only: it never writes to another module.

`loyalty` is deliberately a leaf. An earlier draft had it place gift orders through `order`,
which closed the cycle `order → loyalty → order`; claim codes removed the need entirely. See the
design note in SPEC-loyalty.md.

## Where the complexity lives

`order` owns the only genuinely hard problem in this project: an **orchestrated saga** across
three services with a compensating transaction. Everything else is deliberately thin CRUD,
with one exception: `ai` is thin in data terms but owns the project's only non-deterministic
control flow, a tool-calling loop against a model that decides for itself how many catalogue
lookups a question needs. SPEC-ai.md sets its bounds.

The saga mirrors the Order Flow sheet exactly, including its Failed → Payment Result branch:

```
1. snapshot address     (identity)
2. snapshot prices      (catalog)
3. create order PENDING (local)
4. deduct stock         (inventory)   ──fail──► order FAILED(OUT_OF_STOCK)
5. charge payment       (mock)        ──fail──► COMPENSATE: restore stock (inventory)
                                                 order FAILED(PAYMENT_FAILED)
6. order CONFIRMED, cart cleared
```

Secondary demonstrable problem: **oversell prevention** under concurrency, via an atomic
conditional `UPDATE ... WHERE available >= qty`, backed by `@Version` optimistic locking and a
`CHECK (available >= 0)` constraint on stock rows in `inventory`.

## Decisions taken (2026-09-20)

| Decision | Choice | Why |
|---|---|---|
| Decomposition | 5 services + Eureka | Splitting inventory from catalog is what makes checkout a real distributed transaction |
| Inter-service comms | Sync REST, OpenFeign, orchestrated saga | Traceable and live-demoable; no broker to operate |
| Infrastructure | Spring Cloud Gateway + Eureka | Discovery and load balancing without config-server overhead |
| Persistence | One Postgres, schema per service | Logical DB-per-service, one container |
| Payment | Mocked in `order`, caller-controlled outcome | Both branches of Order Flow demoable on command |
| Stock model | Deduct + restore, no reservation lifecycle | Payment is synchronous and no admin commits reservations; oversell prevention comes from the atomic write, not from reserving. See SPEC-inventory.md |
| Auth | JWT 30-day TTL, no refresh, no logout endpoint | Confirmed with mobile; user re-logs in manually on expiry |
| Password reset | `check-email` then `reset-password`, no token | Explicitly chosen for speed; accepted limitation, see SPEC-identity.md |
