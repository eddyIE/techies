# Spec: order

Module id `order` · port 8084 · schema `orders` · depends on: identity, catalog, inventory
Covers sheet rows 17–18 (Cart), 19–21 (Order), the `/checkout` reference in column I, and the
ORDER-03 Cancel Order screen.

## Objective

Own the cart, the checkout saga, mock payment, and order history. This is the only module with
real distributed complexity; it is where the project earns its grade.

## Data Model

**carts** — id (uuid), user_id (uuid, UNIQUE — one cart per user), created_at, updated_at.

**cart_items** — id, cart_id FK, product_id, quantity (≥1), added_at.
UNIQUE (cart_id, product_id). Carts store *no* price: price is read live for display and
snapshotted only at checkout.

**orders**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| order_ref | varchar(20) | UNIQUE, human-readable e.g. `ORD-20260920-0001` |
| user_id | uuid | indexed |
| status | varchar | see state machine |
| failure_code | varchar | null unless FAILED |
| subtotal, shipping_fee, total | numeric(19,2) | |
| ship_recipient, ship_phone, ship_line1, ship_ward, ship_district, ship_province | varchar | **snapshot** — never re-read from identity |
| payment_method | varchar | `COD` \| `MOCK_CARD` |
| payment_status | varchar | `PENDING` \| `PAID` \| `DECLINED` |
| created_at, updated_at | timestamptz | |

**order_items** — id, order_id FK, product_id, product_name, unit_price, quantity, line_total.
Name and price are **snapshots**: a later price change must not alter a past order.

**saga_steps** — id, order_id FK, step_name, status (`STARTED`/`SUCCESS`/`FAILED`/`COMPENSATED`),
detail (text), created_at. Written at every saga step. This table exists so the saga can be
*shown*, not just described, during the demo.

## Order State Machine

```
           ┌──────────────────────────────┐
PENDING ───┤ stock deducted + payment paid  ├──► CONFIRMED ──► CANCELLED
           └──────────────────────────────┘         (user cancels, stock restored)
   │
   ├── stock deduction failed ────► FAILED (failure_code = OUT_OF_STOCK)
   ├── payment declined ──────────► FAILED (failure_code = PAYMENT_FAILED)   [stock restored]
   └── downstream unreachable ────► FAILED (failure_code = SERVICE_UNAVAILABLE) [stock restored]
```

`FAILED` and `CANCELLED` are terminal. There is no SHIPPED/DELIVERED — no admin site exists to
drive those transitions, so modelling them would be dead code.

## Endpoints

All require a JWT. `userId` always comes from the token, never from the request body.

**Cart** (rows 17–18, plus the two gaps flagged in review):

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/cart` | — | 200 `{items:[{id, productId, name, unitPrice, quantity, lineTotal, available}], subtotal, itemCount}` |
| POST | `/cart/items` | `{productId, quantity}` | 201 — **gap #2**, required by Order Flow "Add to Cart" |
| PUT | `/cart/items/{itemId}` | `{quantity}` | 200 — row 18 |
| DELETE | `/cart/items/{itemId}` | — | 204 — **gap #2** |

`GET /cart` enriches items from `catalog` (batch) and `inventory` (stock) so the Cart screen can
grey out unavailable lines. If either service is down, it degrades: items return with
`available: null` rather than the whole cart erroring.

**Checkout & Orders:**

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/checkout` | `{addressId, paymentMethod, simulatePayment?}` | 200 `{order, paymentResult}` — also 200 on business failure; read `order.status` |
| GET | `/orders` | `?page=&size=&status=` | 200 paginated, newest first — row 21 |
| GET | `/orders/{id}` | — | 200 with items + shipping snapshot — row 20; 403 if not owner |
| POST | `/orders/{id}/cancel` | — | 200 — **gap #3**, required by screen ORDER-03 |

`POST /checkout` returns **200 with a FAILED order**, not a 4xx, when stock or payment fails.
The mobile app needs the order id in both branches to drive the Payment Result screen and its
loop back to Checkout. Transport-level failures still return 5xx.

## The Checkout Saga

Orchestrated in `CheckoutSagaOrchestrator`. Every step writes a `saga_steps` row.

| # | Step | Call | On failure |
|---|---|---|---|
| 1 | Load cart | local | 409 `EMPTY_CART` (no order created) |
| 2 | Snapshot address | `GET identity /internal/addresses/{id}?userId=` | 404 `ADDRESS_NOT_FOUND` (no order created) |
| 3 | Snapshot products | `POST catalog /internal/products/batch` | 409 `PRODUCT_UNAVAILABLE` if any inactive |
| 4 | Persist order `PENDING` | local | — |
| 5 | Deduct stock | `POST inventory /stock/deduct` (orderRef = order_ref) | → `FAILED(OUT_OF_STOCK)`, **stop, no payment attempted** |
| 6 | Charge payment | mock, in-process | → **compensate: `POST inventory /stock/restore`** → `FAILED(PAYMENT_FAILED)` |
| 7 | Clear cart, `CONFIRMED` | local | — |

Seven steps, not eight: with the reservation lifecycle dropped there is no separate commit —
step 5's deduct *is* the commit, and step 6's compensation is what undoes it.

**Steps 1–3 happen before the order row exists** so that trivially invalid checkouts do not
litter order history. From step 4 onward every outcome is a persisted order the app can display.

**The cart is only cleared on success (step 8).** Order Flow shows Payment Result looping back
to Checkout — that retry is impossible if the cart was already emptied. This is deliberate and
is test case #2 in SPEC.md.

**Feign settings:** connect timeout 2s, read timeout 5s. Step 5 retries once on timeout —
safe because `deduct` is idempotent on `order_ref`. Step 6 does not retry.

**Compensation failure is recorded, not swallowed.** If the `restore` call in step 6 itself
fails, the order is still marked `FAILED` and a `saga_steps` row is written with status
`FAILED` naming the stranded `orderRef`. Because `restore` is idempotent on `(order_ref,
RESTORE)`, it is safe to replay by hand from `docs/DEMO.md`.

Documented limitation, and the honest answer if asked: there is no outbox and no retry queue,
so a restore that fails leaves stock understated until someone replays it. Fixing that properly
means an outbox table plus a background publisher — out of scope for this project, and noted in
`docs/EXTENSIONS.md`.

## Mock Payment

`PaymentSimulator` in-process. No external call, no card data, ever.

- `paymentMethod: "COD"` → always succeeds immediately, `payment_status = PAID`.
- `paymentMethod: "MOCK_CARD"` → outcome driven by the optional `simulatePayment` field:

| `simulatePayment` | Behaviour |
|---|---|
| `SUCCESS` (default) | approved after ~200ms |
| `DECLINED` | declined → triggers compensation path |
| `TIMEOUT` | sleeps past the step timeout → treated as declined, compensation path |

A request field, not a config flag, so the lecturer can see both branches back-to-back without
restarting anything.

## Cancellation

`POST /orders/{id}/cancel` — allowed only from `CONFIRMED`, and only by the owner.
1. Guard status; anything else → 409 `ORDER_NOT_CANCELLABLE`.
2. `POST inventory /stock/restore` with the order's items.
3. Mock refund if `payment_status = PAID`.
4. Status → `CANCELLED`.

Cancelling a `FAILED` order is a 409, not a silent success: its stock was already restored by
the saga, and restoring again would invent inventory. Inventory rejects the second restore
anyway (`NOTHING_TO_RESTORE`), so this is guarded on both sides.

## Rules

- `order_ref` format `ORD-yyyyMMdd-NNNN`, per-day sequence from a DB sequence.
- Shipping fee: flat 30,000 VND, free at subtotal ≥ 500,000 VND. Hardcoded constant; no
  shipping service exists and inventing one is out of scope.
- `total = subtotal + shipping_fee`. No tax, no discounts.
- Max 50 distinct lines per cart, max quantity 99 per line.
- Adding a product already in the cart increments quantity; it does not duplicate the line.
- `PUT /cart/items/{id}` with `quantity: 0` deletes the line.
- Cart never validates stock on add — only checkout does. Stock can change between the two, and
  that race is exactly what step 5 exists to resolve.

## Acceptance Criteria

- [ ] Add → update → remove cart item; subtotal recomputes correctly each time.
- [ ] Adding the same product twice yields one line with summed quantity.
- [ ] Happy checkout → `CONFIRMED`, stock down, cart empty, 7 `saga_steps` rows all `SUCCESS`.
- [ ] `simulatePayment=DECLINED` → `FAILED(PAYMENT_FAILED)`, **stock restored exactly**, cart intact,
      a `saga_steps` row with status `COMPENSATED`, and a `RESTORE` movement in inventory.
- [ ] Checkout of a 0-stock product → `FAILED(OUT_OF_STOCK)` and **no payment step recorded**.
- [ ] Checkout with another user's `addressId` → 404, no order row created.
- [ ] Inventory container stopped mid-test → `FAILED(SERVICE_UNAVAILABLE)`, no partial state.
- [ ] `GET /orders/{id}` for another user's order → 403.
- [ ] Cancel a `CONFIRMED` order → stock restored, status `CANCELLED`.
- [ ] Cancel a `FAILED` order → 409, and inventory stock is unchanged.
- [ ] Cancel an already-`CANCELLED` order → 409.
- [ ] A product's price changed after an order was placed → the order still shows the old price.
