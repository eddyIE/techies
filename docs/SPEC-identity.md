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
| email_verified | boolean | false until the mailed code comes back. Accounts predating V3 backfilled to true |
| created_at / updated_at | timestamptz | |

**verification_codes**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK → users | ON DELETE CASCADE |
| purpose | varchar(16) | `REGISTRATION` or `PASSWORD_RESET`, CHECK-constrained |
| code_hash | varchar(72) | BCrypt. The six digits are never stored |
| expires_at | timestamptz | |
| attempts | int | wrong guesses so far |
| created_at | timestamptz | the resend cooldown is read off this |

`UNIQUE (user_id, purpose)` — at most one outstanding code per user per purpose. A code row is
deleted when it is spent, superseded or burned, so that one constraint is also the single-use
rule.

**addresses**

| Column | Type | Notes |
|---|---|---|
| id | uuid PK | |
| user_id | uuid FK → users | indexed |
| recipient_name, phone | varchar | |
| line1, ward, district, province | varchar | |
| is_default | boolean | at most one true per user, enforced in service |
| created_at / updated_at | timestamptz | |

Both verification flows share `verification_codes`; they differ only by `purpose`.

## Endpoints

Public (no JWT):

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/auth/register` | email, password, fullName, phone | 201 `{userId, email, verificationRequired}` — mails a code |
| POST | `/auth/verify-email` | email, code | 200 `{accessToken, tokenType, expiresIn, user}` |
| POST | `/auth/resend-otp` | email, purpose | 204 always |
| POST | `/auth/login` | email, password | 200 `{accessToken, tokenType, expiresIn, user}` — 403 `EMAIL_NOT_VERIFIED` |
| POST | `/auth/check-email` | email | 200 `{email, exists}` |
| POST | `/auth/reset-password` | email, code, newPassword | 204 — 404 `ACCOUNT_NOT_FOUND` if no such account |
| GET | `/users/{id}/avatar` | — | 200 image bytes — 404 if none. Public so image loaders can fetch it |

Authenticated:

| Method | Path | Success |
|---|---|---|
| GET | `/users/me` | 200 UserResponse |
| GET | `/users/{id}` | 200 — 403 unless `{id}` is the caller (sheet row 6 asked for `/user/:id`) |
| PUT | `/users/me` | 200 — updates fullName, phone only |
| PUT | `/users/me/password` | 204 — body: currentPassword, newPassword |
| POST | `/users/me/avatar` | 204 — multipart, field `file`. PNG/JPEG, 2MB max |
| DELETE | `/users/me/avatar` | 204 — 404 if none |
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
### Email verification

- **A 6-digit code, mailed, proves ownership of the address.** It backs both flows: finishing a
  registration, and resetting a password. Gmail SMTP delivers it; `MAIL_USERNAME` is the sender
  and `MAIL_PASSWORD` a Google **app password**, never the account's own password.
- `register` creates the account unverified and mails a `REGISTRATION` code. **Login refuses an
  unverified account with 403 `EMAIL_NOT_VERIFIED`** — and only *after* the password matches, so
  the response never becomes an account-existence oracle.
- `verify-email` returns a token, so the client does not log in a second time.
- `resend-otp` carries a `purpose` and **always answers 204**, including for an address with no
  account and for a registration code on an already-verified one. The response must not become a
  second way to enumerate accounts.
- The forgot-password flow is `resend-otp` with `purpose: PASSWORD_RESET`, then `reset-password`
  with the code. A `PASSWORD_RESET` code is never issued for an account that never verified.
- **Code TTL 10 minutes, 5 wrong guesses, 60-second resend cooldown.** Six digits is only a
  million combinations, so the short life and the attempt cap are most of the strength; the
  cooldown is what stops one user spending the Gmail daily send allowance. An *expired* code is
  replaced without waiting out the cooldown.
- Reaching the attempt cap burns the code. The way back is `resend-otp`, not waiting.
- Only the BCrypt hash is stored. The plaintext exists between being generated and being handed
  to the mailer, and nowhere else, so a database dump holds nothing replayable.
- Sending happens off the request thread and a failed send is logged and dropped. The code is
  already committed by then, and the user's recovery is the same either way: ask for another.
- With `MAIL_USERNAME` unset the endpoints still work and still issue codes; only delivery is
  off. That is what the test suite and an offline demo run on.
- `check-email` still reveals whether an account exists. It is kept because the client's
  forgot-password screen branches on it, and it no longer hands anyone the account.
- Rate limiting beyond the resend cooldown is out of scope, consistent with the rest of the
  project — see `docs/EXTENSIONS.md`.

- **Profile image format is decided by the file's leading bytes**, never by the filename or
  the declared `Content-Type`, both of which the client controls. A declared type that
  disagrees with the bytes is refused rather than stored.
- 2MB limit, enforced in the validator and again by a `CHECK` constraint on the table.
- Uploading replaces any existing image; there is no version history.

## Acceptance Criteria

- [ ] Register → verify with the mailed code → `GET /users/me` returns the registered user.
- [ ] Login on an unverified account → 403 `EMAIL_NOT_VERIFIED`; a *wrong* password on that same
      account → 401 `INVALID_CREDENTIALS`, so the code leaks no existence.
- [ ] The same code cannot be used twice, and a `REGISTRATION` code is refused by
      `reset-password`.
- [ ] Guessing past the attempt cap burns the code: the *correct* code is then refused too.
- [ ] An expired code → `VERIFICATION_CODE_EXPIRED`, and can be replaced without the cooldown.
- [ ] `resend-otp` for an address with no account → 204, and mails nothing.
- [ ] `verification_codes.code_hash` never equals the code that was mailed.
- [ ] Registering a duplicate email (different casing) → 409.
- [ ] `GET /users/{someoneElseId}` → 403.
- [ ] Changing password with a wrong `currentPassword` → 400.
- [ ] After a successful password change, previously issued JWTs still work — asserted as the
      documented consequence of stateless auth with no denylist, so the behaviour is pinned
      rather than accidental.
- [ ] `check-email` on an unknown address → 200 `{exists:false}`; `reset-password` for that
      address → 404.
- [ ] `reset-password` succeeds with the mailed code, and the new password logs in. A weak new
      password is refused *without* spending the code.
- [ ] Creating an address with `isDefault=true` clears the previous default.
- [ ] `password_hash` never appears in any response body or log line.
- [ ] A shell script named `evil.png` and uploaded as `image/png` is refused.
- [ ] A real JPEG uploaded as `image/png` is refused.
- [ ] Exactly 2MB is accepted; one byte more returns 413.
- [ ] `GET /users/{id}/avatar` needs no token, while POST and DELETE do.
- [ ] `avatarUrl` is null until an image exists, then points at the public path.
