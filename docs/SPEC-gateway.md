# Spec: gateway

Module id `gateway` · port 8080 · no schema · depends on: identity (JWT secret only)

## Objective

The only port published to the host. Routes to services by Eureka service id, validates JWTs
once at the edge, and forwards the authenticated identity downstream so no service re-parses
the token.

## Routes

| Path predicate | Service id | Auth | Notes |
|---|---|---|---|
| `/api/auth/**` | identity-service | public | |
| `/api/users/**` | identity-service | JWT | |
| `/api/addresses/**` | identity-service | JWT | |
| `/api/categories/**` | catalog-service | public | |
| `/api/products/**` | catalog-service | public | |
| `/api/stock/{productId}` | inventory-service | public | GET only, read-only badge |
| `/api/cart/**` | order-service | JWT | |
| `/api/checkout` | order-service | JWT | |
| `/api/orders/**` | order-service | JWT | |

`StripPrefix=1` removes `/api` before forwarding.

**Explicitly not routed** — these return 404 at the edge:
`/internal/**` on any service, and all of `/stock/**` except the single GET above
(`/stock/deduct` and `/stock/restore` in particular). Internal
endpoints are reachable only on the compose network, service-to-service.

## Auth Filter

A global `GatewayFilter`:
1. Public predicates pass through untouched.
2. Otherwise require `Authorization: Bearer <jwt>`; missing or malformed → 401 `UNAUTHENTICATED`.
3. Validate signature (HS256, shared secret) and `exp`; invalid → 401 `INVALID_TOKEN`.
4. Inject `X-User-Id` and `X-User-Email` headers downstream.
5. **Strip any inbound `X-User-*` header before step 4** — otherwise a client could forge an
   identity by simply setting the header. This is the single most important line in the module.

Downstream services trust `X-User-Id` because it is unreachable except through the gateway.
Documented limitation: that trust holds only because no service port is published to the host.
Production would need mTLS or per-service token validation.

## Other Concerns

- CORS permissive (`*`) — a mobile client is not browser-origin-bound, and this is a course project.
- Error envelope matches SPEC.md so the client parses gateway and service errors identically.
- Timeouts: connect 2s, response 10s. Checkout is the slowest path; 10s covers it including the
  `TIMEOUT` payment simulation.
- No rate limiting, no request logging beyond Spring defaults. Out of scope.
- Swagger UI is *not* aggregated at the gateway; each service exposes its own on its own port,
  reachable only inside compose or via `docker compose port`.

## Acceptance Criteria

- [ ] `GET /api/products` with no token → 200.
- [ ] `GET /api/cart` with no token → 401 in the standard error envelope.
- [ ] `GET /api/cart` with an expired token → 401 `INVALID_TOKEN`.
- [ ] A forged `X-User-Id` header on an unauthenticated request does not reach order-service.
- [ ] A forged `X-User-Id` alongside a *valid* token for a different user is overwritten with
      the token's user id.
- [ ] `POST /api/stock/deduct` → 404 at the gateway.
- [ ] `GET /api/users/me/../../internal/addresses/x` path traversal → does not reach `/internal/**`.
- [ ] Killing one service leaves the others routable; only its own paths fail.
