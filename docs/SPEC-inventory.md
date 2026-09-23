# Spec: inventory

Module id `inventory` · port 8083 · schema `inventory` · depends on: nothing
Covers the `/stock/:product` reference in the sheet (column I).

## Objective

Own stock truth, the atomic deduction that prevents overselling, and the compensating restore
that the checkout saga calls when payment fails.

This service carries both problems the project exists to demonstrate:

1. **Oversell prevention under concurrency** — two buyers, one remaining unit, one winner.
2. **The compensating half of a distributed transaction** — payment failed in `order`, so the
   stock this service already deducted must come back.

> **Design note (decided 2026-09-20).** An earlier draft specced a three-phase
> `reserve → commit → release` lifecycle. It was dropped. A reservation holds stock across a
> slow, failure-prone gap — an async payment, a human fulfilment step. This system has neither:
> payment is an in-process mock resolving in ~200ms, and with no admin site nothing external
> ever commits a reservation. The `HELD` state would have been created and resolved inside a
> single synchronous request. Oversell prevention comes from the atomic conditional write, not
> from reservation, so nothing was lost by removing it. See `docs/EXTENSIONS.md` for exactly
> when reservation would become necessary.

## Data Model

**stock_items**

| Column | Type | Notes |
|---|---|---|
| product_id | uuid PK | mirrors `catalog.products.id`; no FK, different schema |
| available | int | `CHECK (available >= 0)` |
| version | bigint | `@Version`, optimistic lock |
| updated_at | timestamptz | |

**stock_movements** — the idempotency key and the audit trail in one table.

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| order_ref | varchar(20) | indexed |
| type | varchar | `DEDUCT` \| `RESTORE` |
| created_at | timestamptz | |

UNIQUE `(order_ref, type)` — this single constraint is what makes both operations idempotent.

**stock_movement_items** — id, movement_id FK, product_id, quantity.

## Endpoints

| Method | Path | Body | Success | Failure |
|---|---|---|---|---|
| GET | `/stock/{productId}` | — | 200 `{productId, available, inStock}` | 404 unknown product |
| POST | `/stock/deduct` | `{orderRef, items:[{productId, quantity}]}` | 200 `{orderRef, movementId, deducted:true}` | 409 `INSUFFICIENT_STOCK` |
| POST | `/stock/restore` | `{orderRef, items:[{productId, quantity}]}` | 200 `{orderRef, movementId, restored:true}` | 409 `NOTHING_TO_RESTORE` |

`GET /stock/{productId}` is routed publicly by the gateway (product detail needs an in-stock
badge). `deduct` and `restore` are internal — reachable only on the compose network.

## Semantics

**deduct** — one transaction, all-or-nothing across every line:

```sql
UPDATE stock_items
   SET available = available - :qty, version = version + 1
 WHERE product_id = :id AND available >= :qty
```

Zero rows updated on any line → the whole transaction rolls back, 409 with the offending
product ids. Partial deductions never exist. A `stock_movements` row of type `DEDUCT` is written
in the same transaction.

**restore** — the compensating transaction. `available += qty` for each line, plus a `RESTORE`
movement row. Stock returns to exactly its pre-deduct value.

Restore is used for *both* failure paths — payment declined during checkout, and user
cancellation of a confirmed order. They are the same operation, so unlike the earlier draft
there is no `release` / `return` distinction to get wrong.

**Idempotency.** Both operations are keyed on `(order_ref, type)`:
- `deduct` twice with the same `orderRef` → second call is a no-op, returns 200 with the
  existing `movementId`. Stock moves once. This is what makes the order-service retry safe.
- `restore` twice → same.
- `restore` for an `orderRef` that was never deducted → 409 `NOTHING_TO_RESTORE`. Restoring
  stock that was never taken would invent inventory, so it fails loudly rather than silently.

**Concurrency.** Three layers, deliberately overlapping:
1. The `available >= :qty` predicate in the UPDATE — the primary guard, atomic at the DB.
2. `@Version` optimistic locking — retried once on `OptimisticLockException`, then 409.
3. `CHECK (available >= 0)` — a backstop so that even a logic bug cannot oversell.

Ten threads racing for one unit: exactly one 200, nine 409s, `available` lands at 0.

## Seed Data

`V2__seed_stock.sql` inserts one row per product id from `docs/SEED-IDS.md`. Quantities are
varied on purpose:
- most products: 50–200 units
- **≥3 products: exactly 1 unit** — the concurrency/oversell demo
- **≥2 products: 0 units** — the `OUT_OF_STOCK` checkout branch

These product ids are listed in `docs/DEMO.md` so the demo is copy-pasteable.

## Acceptance Criteria

- [ ] Deduct 2 lines where the second is short → 409, **neither** line deducted.
- [ ] Deduct → restore → `available` equals the original value exactly.
- [ ] Same `orderRef` deducted twice → stock moves once, second call 200 with same `movementId`.
- [ ] Restore for an unknown `orderRef` → 409 `NOTHING_TO_RESTORE`.
- [ ] 10 parallel threads deducting the last unit → exactly 1 succeeds, `available` = 0, and the
      `CHECK` constraint is never violated.
- [ ] `GET /stock/{id}` on a 0-stock product → 200 with `inStock: false`.
- [ ] Direct `POST /api/stock/deduct` through the gateway → 404.
