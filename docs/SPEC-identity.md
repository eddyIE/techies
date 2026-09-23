# Spec: identity

Module id `identity` · port 8081 · schema `identity` · depends on: nothing
Covers sheet rows 1–5 (Auth), 6–8 (User), 9–12 (Address).

## Objective

Own user accounts, authentication, and delivery addresses. It is the only service that
issues JWTs and the only one that stores credentials.

## Data Model

**users**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| email | varchar(255) | UNIQUE, lowercased on write |
| password_hash | varchar(72) | BCrypt, strength 10 |
| full_name | varchar(120) | |
| phone | varchar(11) | |
| created_at / updated_at | timestamptz | |

**addresses**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK → users | indexed |
| recipient_name, phone | varchar | |
| line1, ward, district, province | varchar | |
| is_default | boolean | at most one true per user, enforced in service |
| created_at / updated_at | timestamptz | |

No password-reset token table — see the reset flow below.

## Endpoints

Public (no JWT):

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/auth/register` | email, password, fullName, phone | 201 `{userId, email}` |
| POST | `/auth/login` | email, password | 200 `{accessToken, tokenType, expiresIn, user}` |
| POST | `/auth/check-email` | email | 200 `{email, exists}` |
| POST | `/auth/reset-password` | email, newPassword | 204 — 404 `ACCOUNT_NOT_FOUND` if no such account |

Authenticated:

| Method | Path | Success |
|---|---|---|
| GET | `/users/me` | 200 UserResponse |
| GET | `/users/{id}` | 200 — 403 unless `{id}` is the caller (sheet row 6 asked for `/user/:id`) |
| PUT | `/users/me` | 200 — updates fullName, phone only |
| PUT | `/users/me/password` | 204 — body: currentPassword, newPassword |
| GET | `/addresses` | 200 list, default address first |
| POST | `/addresses` | 201 |
| PUT | `/addresses/{id}` | 200 |
| DELETE | `/addresses/{id}` | 204 |

Internal (called by `order` only, not routed publicly by the gateway):

| Method | Path | Purpose |
|---|---|---|
| GET | `/internal/addresses/{id}?userId=` | Address snapshot at checkout. 404 if not owned by `userId`. |

## Rules

- Email is unique, compared case-insensitively. Duplicate register → 409 `EMAIL_ALREADY_EXISTS`.
- Password policy: ≥8 chars, at least one letter and one digit. Weak → 400 `WEAK_PASSWORD`.
- Login failure returns 401 `INVALID_CREDENTIALS` — identical response whether the email is
  unknown or the password is wrong.
- JWT claims: `sub` (user id), `email`, `iat`, `exp`. **TTL 30 days.** HS256, secret from env,
  shared with the gateway.
- **No refresh token endpoint and no `/logout`** (sheet row 7 is cut). On expiry the user logs
  in again; the client simply discards the token to "log out". Consequence to state plainly if
  asked: a token cannot be revoked before its 30 days are up, because nothing server-side tracks
  it. Revocation would need a denylist — see `docs/EXTENSIONS.md`.
- Deleting the default address promotes the most recently created remaining address.
- A user with zero addresses cannot check out — enforced by `order`, not here.
- **Password reset carries no proof of ownership.** `check-email` reveals whether an account
  exists, and `reset-password` then changes that account's password given only the email. Anyone
  who knows a user's email can take the account. This was chosen deliberately to keep the
  project quick, with no email infrastructure. It is an accepted, documented limitation — not an
  oversight — and `docs/EXTENSIONS.md` records what a real implementation needs.
- Both reset endpoints are rate-limit-free, consistent with the rest of the project.

## Acceptance Criteria

- [ ] Register → login → `GET /users/me` returns the registered user.
- [ ] Registering a duplicate email (different casing) → 409.
- [ ] `GET /users/{someoneElseId}` → 403.
- [ ] Changing password with a wrong `currentPassword` → 400.
- [ ] After a successful password change, previously issued JWTs still work — asserted as the
      documented consequence of stateless auth with no denylist, so the behaviour is pinned
      rather than accidental.
- [ ] `check-email` on an unknown address → 200 `{exists:false}`; `reset-password` for that
      address → 404.
- [ ] `reset-password` succeeds with only email + newPassword, and the new password logs in.
- [ ] Creating an address with `isDefault=true` clears the previous default.
- [ ] `password_hash` never appears in any response body or log line.
