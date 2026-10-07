# Spec: loyalty

Module id `loyalty` · port 8086 · schema `loyalty` · depends on: nothing
Covers no row in the sheet. Added 2026-10-07, after the original five modules shipped.

## Objective

Own customer points, the tier ladder they unlock, the reward vouchers each tier grants, and the
gift catalogue those points are spent on.

The module is deliberately a leaf: it calls no other service. Points arrive because
`order` tells it an order completed, and vouchers are spent because `order` asks it to consume
one at checkout. Both arrows point `order → loyalty`, so nothing here depends on anything else
and the dependency graph stays acyclic.

> **Design note (decided 2026-10-07).** Three earlier drafts were dropped, each for the same
> reason: there is no fulfilment actor in this system, so an elaborate delivery path models
> realism that cannot be demonstrated.
>
> A **gift order** placed through `order-service` was specced first. It created the cycle
> `order → loyalty → order`, needed a second three-service saga, and touched five services —
> four to five days of work whose only visible output was a row nobody could ship.
>
> A **standalone redemption with an address snapshot** removed the cycle but kept a second
> thing-with-an-address-and-a-status alongside orders, and its status had no actor either.
>
> A **free gift riding the next order** was the most elegant, but it defers the reward until
> the customer next buys something, which the mobile design does not expect.
>
> What shipped instead is a **claim code**, collected in store. cellphones.com.vn, the site
> this catalogue was crawled from, is a physical retail chain, so counter collection is how
> that business genuinely works. It removes the address, the shipping, the stock movement and
> the saga. The missing actor does not disappear — nothing marks a code collected — but
> collection is now legitimately outside the system rather than an unreachable state inside it.
> See `docs/EXTENSIONS.md` for what delivered gifts would cost.

## Data Model

**points_ledger** — append-only. Nothing is ever updated or deleted.

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| user_id | uuid | indexed with `created_at DESC` for the history screen |
| entry_type | varchar(16) | `ORDER_EARN` \| `GIFT_SPEND` |
| points | int | signed: positive earns, negative spends |
| reference | varchar(32) | the `order_ref` that earned it, or the redemption id that spent it |
| created_at | timestamptz | |

```sql
CONSTRAINT ck_points_sign CHECK (
    (entry_type = 'ORDER_EARN' AND points > 0) OR
    (entry_type = 'GIFT_SPEND' AND points < 0))
```

UNIQUE `(entry_type, reference)` — the idempotency key, exactly as `stock_movements` uses
`(order_ref, type)`. One earn per order ever, one spend per redemption ever. A retried call
collides here instead of crediting twice.

**Both balances are derived, never stored.** `balance = SUM(points)`,
`lifetime = SUM(points) WHERE points > 0`. A stored counter is a second source of truth that
can drift from the ledger explaining it, and at this scale the sum is free.

**tiers** — the ladder as data, so `GET /loyalty/me` can return the thresholds and the app can
show progress without hardcoding them.

| Column | Type | Notes |
|---|---|---|
| tier | smallint PK | `1`, `2`, `3`. Tier 0 is implicit: below tier 1's threshold |
| threshold_points | int | `10000`, `30000`, `60000` |
| voucher_discount_percent | smallint | `10`, `30`, `50` |

**gifts**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| name | varchar(200) | |
| description | varchar(500) | |
| image_url | varchar(500) | |
| points_cost | int | `CHECK (points_cost > 0)` |
| min_tier | smallint | `CHECK (min_tier BETWEEN 0 AND 3)` |
| stock | int | `CHECK (stock >= 0)` |
| active | boolean | |

**gift_redemptions**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| user_id | uuid | indexed with `claimed_at DESC` |
| gift_id | uuid | FK `gifts` |
| code | varchar(20) | UNIQUE |
| gift_name | varchar(200) | SNAPSHOT, like order line names |
| points_spent | int | SNAPSHOT |
| claimed_at | timestamptz | |

UNIQUE `(user_id, gift_id)` — **this single constraint is the whole re-claim rule.** A customer
can never claim the same gift twice, however many points they hold, and a duplicated request
collides here rather than needing a separate idempotency key.

`gift_name` and `points_spent` are snapshots for the same reason order lines snapshot prices:
repricing or renaming a gift later must not rewrite what a past claim shows on the claimed-gifts
screen.

**tier_vouchers**

| Column | Type | Notes |
|---|---|---|
| code | varchar(32) PK | |
| user_id | uuid | indexed |
| tier | smallint | the tier that granted it |
| discount_percent | smallint | `CHECK (discount_percent BETWEEN 1 AND 100)` |
| issued_at | timestamptz | |
| expires_at | timestamptz | NULL means never, matching `orders.coupons` |
| consumed_order_ref | varchar(20) | NULL until spent |
| consumed_at | timestamptz | |

UNIQUE `(user_id, tier)` — one voucher per tier per customer, ever, which makes issuing
idempotent. Lifetime points never fall, so a tier cannot legitimately be reached twice anyway;
the constraint is what guarantees a replayed award does not mint a second voucher.

## Endpoints

| Method | Path | Body | Success | Failure |
|---|---|---|---|---|
| GET | `/loyalty/me` | — | 200 `{lifetimePoints, balance, tier, pointsToNextTier, tiers:[...]}` | — |
| GET | `/loyalty/gifts` | — | 200 `[{id, name, description, imageUrl, pointsCost, minTier, inStock, eligible, alreadyClaimed}]` | — |
| POST | `/loyalty/gifts/{id}/claim` | — | 200 `{redemptionId, code, giftName, pointsSpent, balanceAfter, claimedAt}` | 404 unknown gift · 409 `GIFT_ALREADY_CLAIMED` \| `INSUFFICIENT_POINTS` \| `TIER_TOO_LOW` \| `GIFT_OUT_OF_STOCK` |
| GET | `/loyalty/claimed-gifts` | — | 200 `[{redemptionId, code, giftName, imageUrl, pointsSpent, claimedAt}]` | — |
| GET | `/loyalty/vouchers` | — | 200 `[{code, tier, discountPercent, issuedAt, expiresAt, consumedAt}]` | — |
| POST | `/loyalty/points` | `{orderRef, userId, amountSpent}` | 200 `{awarded, points, tier, vouchersIssued:[...]}` | — |
| POST | `/loyalty/vouchers/{code}/consume` | `{userId, orderRef, subtotal}` | 200 `{code, discount}` | 409 `VOUCHER_*` |
| POST | `/loyalty/vouchers/{code}/release` | `{orderRef}` | 200 `{code, released:true}` | 409 `NOTHING_TO_RELEASE` |

The five `GET`/`claim` endpoints are routed publicly by the gateway under `/api/loyalty/**`,
which validates the JWT and injects `X-User-Id`. The last three are internal, reachable only on
the compose network: `order-service` is the sole caller and a customer must never be able to
award themselves points.

Every response states the tier as the number `0`–`3` and never names it. Naming is the app's
decision, so "Đồng / Bạc / Vàng" or anything else can change without a server release.

## Semantics

**Earning.** `order-service` calls `POST /loyalty/points` when an order reaches `COMPLETED`, and
at no other time.

```
points = floor((subtotal - discount) / 1000)
```

`subtotal - discount` is what the customer actually paid for goods. `shipping_fee` is excluded
because delivery is not spend, and the raw `subtotal` is wrong because a half-price coupon would
otherwise still earn full points. Floored, so 10.999.500đ earns 10.999 points and never rounds up
into a tier.

> Nothing reaches `COMPLETED` on its own. Only the demo endpoint `PUT /orders/{id}/status` sets
> it, so a demo must push each order's status by hand before points appear. This is the same
> missing-actor gap `SPEC-order.md` already records against `COMPLETED`, not a new one.

**Tier.** Derived from lifetime points against the `tiers` table, and it therefore never falls.
Spending points on a gift reduces the balance and leaves the tier untouched — the alternative
demotes customers for using the feature.

**Voucher issuing.** An award that crosses one or more thresholds issues a voucher per threshold
crossed, in the same transaction as the ledger row. A single large order can cross two at once
and must grant both, so the check iterates the ladder rather than testing only the next rung.

**Claiming a gift.** One local transaction, no saga, because every table involved belongs to this
schema:

1. tier from lifetime points, against the gift's `min_tier` → 409 `TIER_TOO_LOW`
2. balance from the ledger, against `points_cost` → 409 `INSUFFICIENT_POINTS`
3. `UPDATE gifts SET stock = stock - 1 WHERE id = :id AND stock >= 1` → 0 rows means
   409 `GIFT_OUT_OF_STOCK`
4. insert `gift_redemptions` → UNIQUE `(user_id, gift_id)` means 409 `GIFT_ALREADY_CLAIMED`
5. insert the negative `GIFT_SPEND` ledger row

Step 3 is the same conditional-update trick `inventory` uses on `stock_items`, with the same
`CHECK` as a backstop. Any failure rolls the whole transaction back, so points are never spent
against a gift that was not reserved and stock never moves for a claim that did not complete.

**Claim codes** are random, not sequential. A code is worth money at a counter, so a guessable
`GIFT-000124` would let someone collect a gift they never earned. The format is
`GIFT-XXXX-XXXX` over an alphabet with the ambiguous characters removed (no `O`/`0`, no
`I`/`1`/`L`), because a human reads it off a phone screen and types it at a till.

**A redemption is terminal.** There is no status column and no collection step: clicking claim is
the whole transaction, the gift moves to the claimed-gifts screen, and the code stays readable
there indefinitely. What happens next is entirely manual — the customer walks into a store and
hands over the code — and the app is never told about it. This is the deliberate consequence of
having no admin actor: rather than add a `collected` flag that only a demo endpoint could ever
set, collection is left outside the system where it genuinely happens. Codes therefore never
expire, because an expiry nothing can renew would strand a reward the customer paid points for.

**Voucher consume and release** mirror `deduct` and `restore` in `inventory`, deliberately:
checkout consumes before payment so two parallel checkouts cannot both spend one voucher, and the
saga releases it on any downstream failure. The checkout saga in `SPEC-order.md` gains one
participant:

```
1. snapshot address        (identity)
2. snapshot prices         (catalog)
3. consume voucher         (loyalty)    ──fail──► order FAILED(VOUCHER_INVALID)
4. create order PENDING    (local)
5. deduct stock            (inventory)  ──fail──► COMPENSATE: release voucher
                                                  order FAILED(OUT_OF_STOCK)
6. charge payment          (mock)       ──fail──► COMPENSATE: restore stock, release voucher
                                                  order FAILED(PAYMENT_FAILED)
7. order CONFIRMED, cart cleared
```

`consume` is idempotent on `(code, order_ref)`: a retry returns 200 with the same discount rather
than refusing. `release` for a code that was never consumed is 409 `NOTHING_TO_RELEASE`, because
releasing an unspent voucher would invent one — the same reasoning as `NOTHING_TO_RESTORE`.

`release` is called on **three** paths, not two. `SPEC-inventory.md` already uses one `restore`
for both payment failure and user cancellation, and a voucher must follow it: cancelling a
confirmed order returns the stock, so it has to return the voucher too. Leaving it consumed would
silently destroy a reward the customer earned, on the one path where nothing went wrong and they
simply changed their mind.

**Vouchers are not coupons.** `orders.coupons` is fixed-amount, global and infinitely reusable,
and cannot express a per-customer, single-use, percentage discount. Checkout therefore resolves a
submitted code locally first and falls through to `loyalty` if it is not a known coupon. One code
per order either way, and the resulting discount is snapshotted onto the order exactly as a coupon
discount already is, so `ck_orders_discount_within_subtotal` still guarantees a non-negative
total.

## Seed Data

`V1__init.sql` seeds the three tiers and a gift catalogue spanning the ladder, so every branch of
`claim` is demoable:

- **≥2 gifts at `min_tier` 0** — claimable from a standing start
- **≥1 gift per tier 1, 2 and 3** — the `TIER_TOO_LOW` branch, and the reward for climbing
- **≥1 gift with `stock` 0** — the `GIFT_OUT_OF_STOCK` branch
- **≥1 gift priced above any seeded balance** — the `INSUFFICIENT_POINTS` branch

Walking the ladder needs purchases of a known value, so `catalog` seeds three demo products and
`inventory` stocks them at 999 units. At 1 point per 1.000đ they step a fresh account from tier 0
to tier 3 in three orders:

| Product | Price | Bought at | Reaches |
|---|---|---|---|
| Demo A | 10.000.000đ | tier 0 | tier 1 — 10.000 points |
| Demo B | 20.000.000đ | tier 1 | tier 2 — 30.000 points |
| Demo C | 30.000.000đ | tier 2 | tier 3 — 60.000 points |

They live in their own category so the app can keep them out of the real catalogue rather than
showing customers a product called "Demo A". Ids go in `docs/SEED-IDS.md` and the walkthrough in
`docs/DEMO.md`.

## Acceptance Criteria

- [ ] A `COMPLETED` order of 10.000.000đ with no coupon credits exactly 10.000 points.
- [ ] The same `orderRef` awarded twice credits once; the second call returns the first result.
- [ ] A coupon-discounted order credits on `subtotal - discount`, not `subtotal`.
- [ ] `shipping_fee` never contributes points.
- [ ] Crossing tier 1 issues exactly one voucher at 10%; replaying that award issues no second.
- [ ] One order crossing tiers 1 and 2 together issues both vouchers.
- [ ] Claiming a gift leaves `lifetimePoints` unchanged and reduces `balance` by `pointsCost`.
- [ ] Claiming the same gift twice → 409 `GIFT_ALREADY_CLAIMED`, points spent once, stock moved once.
- [ ] Claiming a `min_tier` 2 gift at tier 1 → 409 `TIER_TOO_LOW`, nothing written.
- [ ] Claiming a 0-stock gift → 409 `GIFT_OUT_OF_STOCK`, no ledger row.
- [ ] 10 parallel claims of a 1-stock gift by 10 users → exactly 1 succeeds, `stock` lands at 0.
- [ ] A claimed code reappears verbatim in `GET /loyalty/claimed-gifts` after re-login.
- [ ] Two codes generated back to back are not sequential or guessable from each other.
- [ ] Checkout consuming a voucher then failing payment leaves the voucher unconsumed.
- [ ] Two parallel checkouts with one voucher code → exactly one consumes it.
- [ ] Cancelling a CONFIRMED order that used a voucher returns the voucher to unconsumed.
- [ ] `release` for a never-consumed code → 409 `NOTHING_TO_RELEASE`.
- [ ] Direct `POST /api/loyalty/points` through the gateway → 404.
- [ ] `GET /loyalty/me` for an account with no orders → tier 0, balance 0, no vouchers.
