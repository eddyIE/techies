# Deferred Extensions

Things this project deliberately does **not** do, why, and what adding them would take.
This file exists so that "we didn't build it" can be answered with "here is exactly what it
would cost", rather than a shrug.

## 1. Stock reservation lifecycle

**Not built.** Inventory does an atomic `deduct` and a compensating `restore`.

**When it would be needed.** A reservation holds stock across a gap between *intent to buy* and
*confirmed sale*. That gap must be long and failure-prone to be worth modelling:
- Payment is asynchronous — a bank redirect, a VNPay/Momo callback, a webhook arriving minutes later.
- A human or admin step sits between order placement and fulfilment.
- Stock is held during a multi-step checkout the user can abandon mid-way.

None apply here: payment is an in-process mock resolving in ~200ms, and with no admin site
nothing external ever advances an order.

**What adding it costs.** `reservations` + `reservation_items` tables, a `HELD → COMMITTED |
RELEASED | EXPIRED` state machine, `reserve`/`commit`/`release` endpoints, and a `@Scheduled`
sweeper to expire abandoned holds. The oversell protection would not improve — that comes from
the atomic conditional write, which is already there.

## 2. Compensation retry (transactional outbox)

**Not built.** If the `restore` call fails during checkout compensation, the order is still
marked `FAILED` and a `saga_steps` row records the stranded `orderRef`. Stock stays understated
until someone replays it by hand.

**What it costs.** An `outbox` table written in the same transaction as the order state change,
plus a background publisher that retries with backoff until the downstream call succeeds.
`restore` is already idempotent on `(order_ref, RESTORE)`, so replay is safe today — only the
automation is missing.

## 3. JWT revocation

**Not built.** Tokens live 30 days and cannot be invalidated early. Changing a password does not
end existing sessions, and `POST /logout` is cut entirely — the client just discards the token.

**What it costs.** Either a server-side denylist (Redis, keyed by token id, TTL = remaining
lifetime) checked at the gateway, or short-lived access tokens plus a refresh endpoint. Both
reintroduce the shared state that stateless JWT was chosen to avoid.

## 4. Password reset ownership proof

**Not built.** `check-email` reveals account existence; `reset-password` then changes the
password given only that email. Anyone who knows an address can take the account.

**What it costs.** A single-use, short-lived reset token delivered out of band — email (SMTP or
a provider) or SMS. The token table is trivial; the delivery channel is the actual work, and is
why it was cut.

## 5. Order fulfilment states

**Not built.** There is no `SHIPPED`/`DELIVERED`, and no process that moves an order on by
itself. `COMPLETED` exists but only `PUT /orders/{id}/status` sets it, by hand, for a demo.

**Why.** With no admin site, nothing could ever drive those transitions on its own. They would
be unreachable states — dead code that looks like features.

## 6. Real payment integration

**Not built.** `PaymentSimulator` runs in-process with a caller-controlled outcome.

**What it costs.** A payment service, provider SDK, a signed webhook receiver, idempotent
callback handling, and reconciliation. Notably, this is the change that would make extension #1
genuinely necessary.

## 7. Delivered gifts

**Not built.** Claiming a gift returns a code. The customer takes it to a store and hands it
over; the app is never told what happened next.

**Why.** A delivered gift needs an address, a shipment, stock movement and a status someone
advances — and there is no fulfilment actor in this system to advance it, exactly as in
extension #5. Three drafts were written and dropped before claim codes: a gift order placed
through `order-service` (which closed the cycle `order → loyalty → order` and needed a second
three-service saga), a standalone redemption with its own address snapshot, and a free gift
riding the customer's next order. The first two added a second thing-with-a-status that nothing
could move; the third defers the reward until the customer happens to buy again.

The catalogue was crawled from cellphones.com.vn, a physical retail chain, so collection at a
counter is how that business genuinely works. The missing actor does not disappear — it is
moved outside the system, where it is real, instead of being modelled as an unreachable state
inside it.

**What it costs.** An address snapshot on the redemption, a shipment entity, a fourth saga
participant for gift stock, and an admin surface to mark a gift dispatched. Four to five days,
most of it to produce a status nobody can change.

## 8. Gift collection tracking

**Not built.** `gift_redemptions` has no status column. A code is issued and that is the end
of it, as far as the backend knows.

**Why.** The only thing that could set `collected` is a demo endpoint, and a flag that exists
solely so a demo can flip it is the dead code extension #5 warns about. Codes therefore never
expire either: an expiry nothing can renew would strand a reward the customer paid points for.

**What it costs.** Little in code — a `collected_at` column and a staff endpoint to stamp it —
and a lot in everything around it: an authenticated staff role, a till-side screen or scanner,
and a story for a code presented at a store that cannot reach the API. The code is cheap; the
actor is not.

## 9. Points as a direct discount

**Not built.** Points buy gifts. Tiers grant percentage vouchers. Points never convert into
money off an order.

**Why.** Explicitly specified that way. One-way conversion keeps the ledger a ledger: a balance
is only ever spent on a catalogue item at a known price, so there is no exchange rate to
maintain, no rounding to argue about, and no interaction between a points discount and a
coupon or a voucher on the same order.

**What it costs.** A fourth kind of discount in the checkout saga, a `POINTS_SPEND` ledger
entry tied to an order rather than a redemption, and a rule for how it stacks with the other
three. The stacking rules are the expensive part, not the arithmetic.

## 10. Operational concerns

Out of scope, listed for completeness: rate limiting, distributed tracing, centralised logging,
metrics, circuit breakers (Resilience4j), config server, API versioning, database read replicas,
caching, and CI/CD.
