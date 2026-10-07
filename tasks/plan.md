# Implementation Plan

Specs: `docs/SPEC.md` + `docs/SPEC-<module>.md`, indexed by `docs/CAPABILITY-MAP.md`.

## Component dependency graph

```
        ┌─────────────────────────────────────────┐
        │ A. Foundation                           │  sequential, blocks all
        │ parent POM · common · compose · Eureka  │
        └────────────────────┬────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
   B1. identity         B2. catalog         B3. inventory      ← parallelisable
        └────────────────────┼────────────────────┘
                             ▼
                        C. order                               ← needs all three
                             ▼
                        D. gateway                             ← needs routes to exist
                             ▼
                     E. integration + demo
```

## Build order and rationale

**A — Foundation first, and verified before anything else.** The single highest-risk item in
this project is the Spring Boot ↔ Spring Cloud version pairing. A wrong pin surfaces as
incomprehensible bean-wiring failures three modules later. Task A1 does nothing but prove the
skeleton compiles and boots.

**B — Three leaf services, no dependencies between them.** identity, catalog and inventory
import nothing from each other and can be built in any order. Each is finished and tested before
`order` is started, so when the saga misbehaves the fault is in the saga, not underneath it.

**C — order last among the services.** It is the only module with real complexity, and it needs
all three leaves reachable to test against. Building it first would mean stubbing three services
and then re-testing everything once they were real.

**D — gateway after the routes exist.** Routing to services that do not yet respond makes the
auth filter untestable end-to-end.

**E — integration proves the 7 non-negotiable cases** from `SPEC.md § Testing Strategy` against
a real compose stack, then `DEMO.md` makes it reproducible by hand.

## Verification checkpoints

Hard gates. Work does not proceed past a failing checkpoint.

| After | Must be true |
|---|---|
| A | `./mvnw clean install` green; Eureka UI reachable; Postgres has 4 schemas |
| B | Each service registers with Eureka, serves its endpoints, own tests green |
| B3 | **10-thread concurrent deduct test passes** — the oversell proof |
| C | Checkout succeeds and fails correctly against live services; `saga_steps` populated |
| C | **Payment-failure path restores stock to its exact prior value** — the compensation proof |
| D | 401/403 behaviour correct; forged `X-User-Id` cannot reach a service |
| E | All 7 non-negotiable cases green; `docker compose up --build` clean from scratch |

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Boot/Cloud version mismatch | Blocks everything | A1 exists solely to prove the pin before module work |
| No JDK/Maven on this machine | Blocks everything | Installing now via Homebrew; A1 re-verifies |
| Docker daemon not running | Blocks Testcontainers + compose | Started; A3 verifies |
| Concurrency test flaky | Undermines the headline demo | Three overlapping guards (conditional UPDATE, `@Version`, CHECK); test asserts final stock, not timing |
| Feign + Eureka service-id resolution | Saga silently unreachable | C4 tested against live services, not mocks |
| Seed id drift between catalog and inventory | Stock attached to nothing | Single source `docs/SEED-IDS.md`; B2 generates it, B3 consumes it |
| Saga leaves partial state | Corrupt orders | Steps 1–3 before order creation; every step logged to `saga_steps` |
| Scope creep into admin/fulfilment | Time loss | `docs/EXTENSIONS.md` is the parking lot |

## Parallelisation

B1/B2/B3 are independent — three agents could run concurrently. Under `/agent-skills:build auto`
they run sequentially in listed order, which is fine; the dependency graph is what matters, not
the wall clock.

Everything else is strictly sequential.

## Definition of done per task

Per `SPEC.md § Boundaries`: tests written and green, `./mvnw test` passes, no `@Disabled`, no
endpoint absent from its module spec, one commit per task.

---

## Second wave (2026-10-07): loyalty and the AI review summary

Specs: `docs/SPEC-loyalty.md`, plus new sections in `docs/SPEC-order.md` and `docs/SPEC-ai.md`.

```
   F. loyalty            ← a leaf: calls nothing, so it blocks only G
        │
        ├──► G. order ↔ loyalty      (points on COMPLETED, voucher in the saga)
        │
   H. review summary     ← independent of F; ai-service + order-service only
        │
        └──► I. gateway route, demo products, docs
```

**F first, and alone.** `loyalty` calls no other service, so it can be built and proven green
with no caller at all. That is the whole reason the gift mechanism is a claim code: the rejected
gift-order design made `loyalty` depend on `order`, which already depends on `loyalty`, and the
cycle would have forced both to be built together.

**H is parallelisable with F** — it touches only `ai-service` and `order-service` and shares no
file with the loyalty work. Under `auto` they run in listed order, which is fine.

**I last**, because a gateway route to a service that does not answer yet is untestable, and the
demo products exist only to walk the tier ladder F defines.

### Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| `init.sql` only runs on a fresh volume | A new schema would need a full reseed mid-project | F1 sets `spring.flyway.schemas` so Flyway creates `loyalty` itself; `init.sql` updated for new volumes only |
| Points awarded twice | Free money, and it moves a customer up a tier | UNIQUE `(entry_type, reference)` in the ledger; the retry collides instead of crediting |
| Voucher spent twice | Two orders at one discount | Consume is atomic and happens before payment, mirroring `deduct` |
| Voucher lost on cancellation | Destroys an earned reward on the one path where nothing failed | `release` is specced on three paths, not two; G2 tests all three |
| Loyalty outage blocks checkout | A reward feature taking down the core flow | G1 logs and continues; G2 fails the order only if a voucher was actually submitted |
| Review summary drains the Gemini quota | Paid requests per page view | Cached on `review_count`, so cost is per new review, not per view; endpoint is internal |
| Tier thresholds unreachable in a demo | The feature cannot be shown | I1 seeds three demo products that step 0 → 1 → 2 → 3 in three orders |
