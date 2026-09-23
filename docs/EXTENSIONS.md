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

**Not built.** Orders terminate at `CONFIRMED`, `FAILED` or `CANCELLED`. There is no
`SHIPPED`/`DELIVERED`.

**Why.** With no admin site, nothing could ever drive those transitions. They would be
unreachable states — dead code that looks like features.

## 6. Real payment integration

**Not built.** `PaymentSimulator` runs in-process with a caller-controlled outcome.

**What it costs.** A payment service, provider SDK, a signed webhook receiver, idempotent
callback handling, and reconciliation. Notably, this is the change that would make extension #1
genuinely necessary.

## 7. Operational concerns

Out of scope, listed for completeness: rate limiting, distributed tracing, centralised logging,
metrics, circuit breakers (Resilience4j), config server, API versioning, database read replicas,
caching, and CI/CD.
