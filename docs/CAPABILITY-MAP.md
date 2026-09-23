# Capability Map: E-Commerce Mobile Backend (Nhóm 3)

Source of requirements: `Java - BT Lớn.xlsx` (sheets: Chức năng, List màn hình, Order Flow).

## Modules

| Module id | Responsibility | Depends on |
|---|---|---|
| identity | Register, login, password reset, profile, addresses | — |
| catalog | Categories, product list, search, product detail | — |
| inventory | Stock levels, atomic deduct / compensating restore | — |
| order | Cart, checkout saga, mock payment, orders, cancel | identity, catalog, inventory |
| gateway | Single entry point, JWT validation, routing | identity |

Infrastructure (not capability modules): `discovery-server` (Eureka), `common` (shared DTOs + error model), Postgres, Docker Compose.

**Build order:** identity, catalog, inventory (parallel) → order → gateway

## Dependency direction

```
gateway ──► (routes to all)
order ──► identity   (address snapshot)
      ──► catalog    (price + product snapshot)
      ──► inventory  (deduct / restore)
```

No cycles. `identity`, `catalog` and `inventory` know nothing about `order`.

## Where the complexity lives

`order` owns the only genuinely hard problem in this project: an **orchestrated saga** across
three services with a compensating transaction. Everything else is deliberately thin CRUD.

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
