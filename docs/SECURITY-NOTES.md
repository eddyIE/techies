# Security Notes

What this deployment does and does not protect against. Written down because several of the
weaknesses are deliberate choices for a course project, and a reader should be able to tell
those apart from oversights.

## Exposure

The API is reachable at a public HTTPS URL through an ngrok tunnel to a laptop. There is no
server, no firewall of our own, and no WAF.

## Geo restriction — Vietnam only

`deploy/ngrok.yml.example` restricts traffic to `conn.geo.country_code == 'VN'`, enforced at
ngrok's edge. A blocked request never reaches the gateway or any service, and costs no
upstream work.

Verified in both directions: inverting the rule to block `VN` returned `403` with the custom
JSON body, and restoring it returned `200`. So the expression genuinely evaluates rather than
silently passing everything.

**What it actually buys you.** It removes background internet noise — opportunistic scanners,
credential-stuffing bots and crawlers, which overwhelmingly originate outside Vietnam. That is
worth having.

**What it does not buy you.** It is not a security control:

- **A VPN defeats it in seconds.** Anyone who wants in picks a Vietnamese exit node.
- **It blocks legitimate users.** A teammate travelling, on a corporate VPN that egresses
  abroad, or on a mobile carrier routing through Singapore will get a 403 with no obvious
  explanation. **If anyone reviewing or grading this project is outside Vietnam, they will be
  locked out** — remove the rule before sharing the URL with them.
- **Geo-IP is approximate.** Country attribution from IP is wrong often enough to matter.

Treat it as noise reduction, not as a gate.

## The real weakness, unchanged by any of the above

`POST /auth/check-email` reveals whether an account exists, and `POST /auth/reset-password`
then changes that account's password given only the email address. **Anyone who reaches the
API can take over any account whose email they know.**

This was a deliberate choice to keep the project small — no email infrastructure — and it is
recorded in `docs/SPEC-identity.md` and `docs/EXTENSIONS.md`. The geo rule narrows who can
reach it; it does not fix it.

Consequences to respect while the tunnel is up:

- Register with throwaway emails only.
- Never reuse a password you use anywhere else.
- Stop the tunnel when you are not demonstrating: `pkill -f ngrok`.

The fix is a short-lived reset token, roughly 15 lines, described in `docs/EXTENSIONS.md`.

## The AI assistant sends data to Google

The product assistant calls the Gemini API, so two things leave your infrastructure:

- **Product data** — name, price, category and description from your own catalogue.
- **Whatever the customer types** into the chat.

No account data goes with it: the assistant is given a product and the conversation, never the
user's name, email, cart or order history. The request is authenticated so usage is tied to an
account, but the user's identity is not forwarded to Google.

One detail worth stating: the tool round trip chains with `previous_interaction_id`, which
requires `store: true`, so **Google retains the interaction** on their side. We store nothing —
the app holds the conversation and discards it when the popup closes.

Tell demo users not to type anything personal into the chat. `GEMINI_API_KEY` lives in `.env`,
which is gitignored; a leaked key is someone else spending your quota, not a data breach.

**Quota is a denial-of-service surface.** The free tier allows 20 requests per day and a turn
that searches costs two, so roughly ten searching turns exhaust it. Authentication ties abuse
to an account, but one logged-in user can still empty the day's quota in a minute. A per-user
cap is the obvious next step if this is ever left running unattended.

## Other accepted limitations

| | |
|---|---|
| No rate limiting | Login, registration and reset can be hit as fast as the network allows |
| JWTs cannot be revoked | 30-day lifetime, no denylist; a password change does not end existing sessions |
| No HTTPS between services | Plain HTTP inside the compose network, which is acceptable only because no service port is published |
| Mock payment | No real provider, no card data, nothing to steal |
| Seeded data only | Nothing real in the database |

## What is actually defended

Worth stating, so the list above is not mistaken for "nothing works":

- **Passwords** are BCrypt hashed, never logged, never returned.
- **JWTs** are validated once at the gateway; a bad signature or expiry is rejected at the edge.
- **Identity headers cannot be forged.** The gateway strips any inbound `X-User-*` before
  injecting the authenticated one — pinned by a test.
- **Internal endpoints are unroutable.** `/internal/**`, `/stock/deduct` and `/stock/restore`
  return 404 at the gateway and are reachable only inside the compose network.
- **Ownership is enforced per resource.** Another user's order is a 403, another user's address
  a 404 — never their data.
- **Stock cannot be oversold**, even under concurrent checkout, guarded three ways.
