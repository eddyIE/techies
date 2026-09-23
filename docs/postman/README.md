# Postman collection

Generated from the live API, so the saved examples are real captured responses rather than
hand-written guesses. Regenerate with `python3 scripts/generate-postman.py`.

## Import

In Postman: **Import** → drop in all three files.

| File | |
|---|---|
| `techies.postman_collection.json` | 27 requests in 7 folders, 29 saved example responses |
| `techies-local.postman_environment.json` | `baseUrl` = `http://localhost:8080/api` |
| `techies-shared.postman_environment.json` | `baseUrl` = the public ngrok URL |

Pick an environment from the top-right dropdown.

## Start here

Run **1. Auth → Login**. Its test script stores `accessToken` as a collection variable, and
collection-level bearer auth applies it to every authenticated request — no copy-pasting.

The whole collection also runs top to bottom in the **Postman Runner**. Requests chain through
collection variables: listing products stores a `productId`, creating an address stores an
`addressId`, ordering stores an `orderId`.

Verified with Newman against both the local stack and the public URL: **27 requests, 0
failures**.

## Things worth knowing

**Checkout returns HTTP 200 even when the order fails.** Branch on `order.status`, not the
status code. All three outcomes have saved examples: `CONFIRMED`, `FAILED (PAYMENT_FAILED)`
and `FAILED (OUT_OF_STOCK)`.

**Each checkout variant primes its own cart** via a pre-request script. A successful checkout
empties the cart, so without this every variant after the first would fail with `EMPTY_CART`
and the interesting branches would never run. The out-of-stock variant additionally looks up
a product seeded with zero units.

**`Register` returns 409 after the first run.** The account exists — correct behaviour, and it
demonstrates the duplicate-email path. Change the `email` variable for a fresh account.

**Password requests are deliberately idempotent.** *Reset password* and *Change password* set
the same password back, so the collection stays re-runnable instead of locking itself out.

**Destructive requests run last.** Deleting the address before checkout would break it, so
folder 7 holds those.
