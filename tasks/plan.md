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
