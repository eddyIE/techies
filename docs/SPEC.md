# Spec: E-Commerce Mobile Backend

Root spec. Shared conventions for every module. Module-specific contracts live in
`SPEC-<module-id>.md`, indexed by `CAPABILITY-MAP.md`.

## Objective

A REST API backing an Android shopping app, written in Java, for a university course
project. The app is a demo and is not published to any store.
No admin site; catalog, category and stock data are seeded by migration.

**User:** an Android app consumer who browses products, manages a cart, and places orders.
**Grader:** a lecturer assessing whether the microservice decomposition is real and whether
the hard problem (distributed checkout) is solved rather than hand-waved.

**Explicitly out of scope** — cut deliberately as production-only concerns:
admin/management site, real payment gateway, email/SMS delivery, shipping-carrier
integration, product reviews & ratings, coupons/promotions, wishlist, refunds,
multi-currency, i18n, rate limiting, observability stack.

**Success looks like:** `docker compose up` yields a working API where a user can register,
browse seeded products, build a cart, check out successfully, and — on demand — check out
into a payment failure that provably restores the deducted stock.

## Tech Stack

| Concern | Choice |
|---|---|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.3.x |
| Cloud | Spring Cloud 2023.0.x (Gateway, Eureka, OpenFeign) |
| Build | Maven multi-module |
| DB | PostgreSQL 16, one container, one schema per service |
| Migrations | Flyway, per service |
| Auth | JWT (HS256), stateless, jjwt 0.12.x |
| Mapping | MapStruct |
| Boilerplate | Lombok |
| Docs | springdoc-openapi (Swagger UI per service) |
| Test | JUnit 5, AssertJ, Mockito, Testcontainers, WireMock |
| Runtime | Docker Compose |

**Verified toolchain (2026-09-20):** OpenJDK 21.0.12.1 (Homebrew, keg-only), Apache Maven
3.9.16, Docker 29.6.2 with Compose v5.3.1.

Spring Boot and Spring Cloud patch versions are pinned in the parent `pom.xml` in task A1 and
must be proven to resolve and boot together before any module work starts.

## Commands

**Every Maven command must be preceded by `source scripts/env.sh`.** This machine has two JDKs:
an unversioned Homebrew `openjdk` (Java 27) shadows the keg-only `openjdk@21`, so plain `mvn`
picks Java 27 and `/usr/libexec/java_home -v 21` also returns the 27 JDK. Building without the
export compiles against the wrong JDK.

```bash
source scripts/env.sh          # JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# Build everything, run unit tests
./mvnw clean install

# Unit tests only (fast)
./mvnw test

# Integration tests (Testcontainers; requires Docker running)
./mvnw verify -Pintegration

# Single module
./mvnw -pl order-service -am test

# Run the whole stack
docker compose up --build

# Run the stack, rebuild only one service
docker compose up -d --build order-service

# Tear down including volumes (resets seeded data)
docker compose down -v

# Formatting / static analysis
./mvnw spotless:apply
./mvnw spotless:check
```

## Project Structure

```
techies/
  pom.xml                  → Parent POM: dependency management, shared plugins
  docker-compose.yml       → Postgres + Eureka + 5 services
  docs/                    → CAPABILITY-MAP.md, SPEC.md, SPEC-<module>.md, ADRs
  tasks/                   → plan.md, todo.md (generated in Phase 2/3)
  common/                  → Shared error model, JWT claims, cross-service DTOs
  discovery-server/        → Eureka, port 8761
  api-gateway/             → Spring Cloud Gateway, port 8080 (only public port)
  identity-service/        → port 8081, schema `identity`
  catalog-service/         → port 8082, schema `catalog`
  inventory-service/       → port 8083, schema `inventory`
  order-service/           → port 8084, schema `orders`
```

Inside each service:

```
src/main/java/vn/techies/ecommerce/<module>/
  api/           → @RestController, request/response records
  domain/        → JPA entities, enums, domain exceptions
  repository/    → Spring Data repositories
  service/       → business logic, transaction boundaries
  client/        → Feign clients to other services (order-service only)
  config/        → Spring configuration
src/main/resources/
  application.yml
  db/migration/  → Flyway V1__*.sql, V2__seed_*.sql
src/test/java/   → mirrors main package structure
```

## Code Style

Records for DTOs, constructor injection, no field injection, no `@Autowired` on constructors,
transaction boundaries only in `service/`, controllers stay thin.

```java
@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
class AddressController {

    private final AddressService addressService;

    @GetMapping
    List<AddressResponse> list(@AuthenticationPrincipal UserPrincipal principal) {
        return addressService.listFor(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AddressResponse create(@AuthenticationPrincipal UserPrincipal principal,
                           @Valid @RequestBody CreateAddressRequest request) {
        return addressService.create(principal.userId(), request);
    }
}

record CreateAddressRequest(
        @NotBlank String recipientName,
        @NotBlank @Pattern(regexp = "^[0-9]{9,11}$") String phone,
        @NotBlank String line1,
        @NotBlank String ward,
        @NotBlank String district,
        @NotBlank String province,
        boolean isDefault) {}
```

Conventions:
- Classes package-private unless another package needs them.
- `UPPER_SNAKE` enum constants; enums persisted with `@Enumerated(EnumType.STRING)`.
- Money is `BigDecimal(19,2)`; never `double`.
- Timestamps are `Instant`, stored UTC as `timestamptz`.
- IDs are `UUID`, generated application-side.
- No business logic in entities beyond invariant guards.
- Every Flyway migration is forward-only; never edit an applied migration.

## Error Model

One envelope, every service, returned by a shared `@RestControllerAdvice` in `common`:

```json
{
  "timestamp": "2026-09-20T10:15:30Z",
  "status": 409,
  "code": "INSUFFICIENT_STOCK",
  "message": "Not enough stock for product 7f3a…",
  "path": "/api/checkout",
  "fieldErrors": { "quantity": "must be greater than 0" }
}
```

`code` is a stable machine-readable enum the mobile client switches on. `message` is for
developers, not end users. HTTP status usage: 400 validation, 401 missing/invalid token,
403 authenticated but not owner, 404 not found, 409 business-rule conflict (out of stock,
duplicate email), 422 payment declined, 502 downstream service unavailable.

## Logging

Every service writes `logs/<service>.log` (INFO+) and `logs/<service>-error.log` (WARN+),
configured once in `common/src/main/resources/logback-spring.xml`.

**No failed request is silent.** `GlobalExceptionHandler` is the single choke point that logs
problems, so an individual handler cannot forget to:

| Outcome | Level | Stack trace | Rationale |
|---|---|---|---|
| 5xx | ERROR | yes | A defect; the trace is the evidence |
| 4xx | WARN | no | Expected control flow, but still must be visible |

Two paths sit outside that handler and log themselves, because they never throw:

- **Gateway** — `AUTH REJECTED` and `GATEWAY PROBLEM`, covering failures no service observes:
  an unrouted path (404) and an unreachable service (502).
- **Checkout failure** — answers HTTP 200 with a `FAILED` order by design, so `OrderWriter`
  logs `ORDER FAILED` at WARN.

**Correlation.** Every line carries `[requestId]` and `[userId]` from the MDC. The gateway
mints the id, forwards it as `X-Request-Id`, echoes it to the client, and `FeignTracingConfig`
carries it across service calls, so one `grep` reconstructs a whole distributed transaction.

Never log a password, token, or full `Authorization` header. Internal exception detail belongs
in the log file, never in a response body.

## Testing Strategy

| Level | Scope | Tooling | Where |
|---|---|---|---|
| Unit | Service logic, saga branching, stock arithmetic | JUnit 5 + Mockito | `src/test/.../service/` |
| Slice | Controllers, serialization, validation, auth rules | `@WebMvcTest` | `src/test/.../api/` |
| Repository | Queries, constraints, optimistic locking | `@DataJpaTest` + Testcontainers | `src/test/.../repository/` |
| Integration | Full service against real Postgres, Feign stubbed | `@SpringBootTest` + Testcontainers + WireMock | `src/test/.../integration/` |

**Coverage bar:** ≥80% line coverage on `service/` packages. Elsewhere untargeted — chasing
coverage on DTOs and config is waste.

**Non-negotiable test cases.** These are the ones the lecturer will ask about:
1. Checkout succeeds → order `CONFIRMED`, stock decremented, cart emptied.
2. Checkout with payment failure → stock **restored** to its original value, order
   `FAILED(PAYMENT_FAILED)`, cart **not** emptied so the user can retry (per Order Flow).
3. Checkout with insufficient stock → order `FAILED(OUT_OF_STOCK)`, no payment attempted.
4. Two concurrent checkouts for the last unit → exactly one succeeds, no oversell.
5. Cancelling a confirmed order returns its stock.
6. Deduct is idempotent: same `orderRef` twice does not double-deduct.
7. Inventory unreachable during checkout → order `FAILED`, no partial state, 502 surfaced.

## Boundaries

**Always:**
- Run `./mvnw test` before any commit.
- Write the failing test before the fix, for every bug.
- Validate every request body with Bean Validation.
- Hash passwords with BCrypt. Never log a password, token, or full auth header.
- Snapshot price and address onto the order at checkout — orders must not re-read live data.
- Keep each service's schema private; no cross-schema SQL, no shared entity classes.
- Log every failed request; never swallow an exception without a log line.

**Ask first:**
- Adding a dependency not in the Tech Stack table.
- Changing an inter-service contract after its module spec is approved.
- Any schema change to an already-applied migration.
- Introducing a message broker, cache, or additional infrastructure container.

**Never:**
- Commit secrets. Local credentials live in `.env`, which is gitignored; `.env.example` is committed.
- Call another service's database directly.
- Delete or `@Disabled` a failing test to get the build green.
- Add endpoints not traceable to a row in the `Chức năng` sheet or a documented gap.
- Integrate a real payment provider.

## Success Criteria

1. `docker compose up --build` starts Postgres, Eureka and all 5 services; all register with
   Eureka and report `UP` within 90s on a clean machine.
2. All endpoints in `SPEC-<module>.md` respond per contract through the gateway on `:8080`.
   No service port other than 8080 is published to the host.
3. Seed data loads automatically: ≥6 categories, ≥40 products with images and stock.
4. A protected endpoint without a valid JWT returns 401; with another user's resource, 403.
5. The 7 non-negotiable test cases above pass.
6. `./mvnw clean install` is green with ≥80% line coverage on `service/` packages.
7. Swagger UI is reachable per service and documents every endpoint.
8. A `docs/DEMO.md` walkthrough drives both checkout branches end-to-end with copy-pasteable
   `curl` commands.

## Resolved Decisions

Answered 2026-09-20; recorded here so the reasoning is not lost.

1. **Package root** — `vn.techies.ecommerce` ("Techies" is the store name).
2. **Password reset** — no email, no token. `POST /auth/check-email` returns whether the account
   exists, then `POST /auth/reset-password` takes email + new password directly. Accepted
   limitation: anyone knowing an email can reset that account. Chosen deliberately for speed.
3. **JWT** — 30-day TTL, no refresh endpoint, no `/logout`. On expiry the user logs in again.
   `POST /logout` from sheet row 7 is cut; the client discards the token.
4. **Stock** — reservation lifecycle dropped in favour of deduct + compensating restore.
   Rationale in `SPEC-inventory.md`; deferred alternative in `docs/EXTENSIONS.md`.
5. **Versions** — pinned in the parent POM and verified to build before module work begins.

## Open Questions

None outstanding. New questions are raised here before the affected code is written.
