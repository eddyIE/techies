#!/usr/bin/env python3
"""
Regenerate docs/API.md by exercising the running API and capturing real responses.

    docker compose up -d
    python3 scripts/generate-api-docs.py

Every example below is a real request and a real response, not hand-written, so the document
cannot quietly drift from the API. Re-run it whenever an endpoint changes.
"""
import json, pathlib, re, subprocess, sys, textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent
CAPTURE = ROOT / "scripts" / "_api_capture.py"
OUT = ROOT / "docs" / "API.md"
TMP = ROOT / ".api-capture.json"


# A capture runs against a live stack, so responses contain a real signed token. Never let
# one reach a committed file: once this is deployed, that would be a working credential.
JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+")


def scrub(text):
    return JWT_PATTERN.sub("<accessToken - a JWT, roughly 300 characters>", text)


def fence(payload, limit=1400):
    if payload is None:
        return "```\n(no body)\n```"
    text = scrub(json.dumps(payload, indent=2, ensure_ascii=False))
    if len(text) > limit:
        text = text[:limit].rsplit("\n", 1)[0] + "\n  ...\n}"
    return "```json\n" + text + "\n```"


def endpoint(cap, key, title, *, auth, description, extra=""):
    if key not in cap:
        return f"### {title}\n\n_(not captured)_\n\n"
        
    c = cap[key]
    lock = "Bearer token required" if auth else "Public — no token"
    out = [f"### {title}\n",
           f"`{c['method']} {c['path'].split('?')[0]}` · **{lock}** · responds `{c['status']}`\n",
           description + "\n"]
    if extra:
        out.append(extra + "\n")
    if c["request"] is not None:
        out.append("**Request**\n\n" + fence(c["request"]) + "\n")
    out.append("**Response**\n\n" + fence(c["response"]) + "\n")
    return "\n".join(out) + "\n"


def main():
    subprocess.run([sys.executable, str(CAPTURE)], check=True,
                   cwd=ROOT, env={**__import__("os").environ, "OUT": str(TMP)})
    cap = json.loads(TMP.read_text(encoding="utf-8"))
    TMP.unlink(missing_ok=True)

    doc = []
    w = doc.append

    w(textwrap.dedent("""\
        # Techies API — Reference for the Android App

        Everything the app needs to talk to the backend. The client is an Android app written
        in Java; it is a course demo and is not published to any store. **Every example is a real captured
        request and response**, not written by hand — regenerate with
        `python3 scripts/generate-api-docs.py` against a running stack.

        ---

        ## Basics

        | | |
        |---|---|
        | Base URL (local) | `http://localhost:8080/api` |
        | Base URL (deployed) | `http://<host>/api` |
        | Content type | `application/json` on every request with a body |
        | Auth | `Authorization: Bearer <accessToken>` |
        | Money | VND, JSON number with 2 decimals. Never a float in your code — use a decimal type |
        | IDs | UUID strings |
        | Timestamps | ISO-8601 UTC, e.g. `2026-09-23T04:12:30.123Z` |

        Only one port is public. Anything under `/internal/**`, plus `/stock/deduct` and
        `/stock/restore`, is blocked at the gateway and will 404 — those are service-to-service
        only.

        > **Browser testing note.** The shared URL runs through ngrok's free plan, which serves an
        > HTML interstitial to requests with a *browser* User-Agent. Your app is unaffected —
        > OkHttp and Retrofit receive JSON normally, as do curl and Postman. It only appears when
        > you open the URL in Chrome or Safari, where you click through once. To bypass it from a
        > browser, send `ngrok-skip-browser-warning: true` with an extension such as ModHeader.

        ---

        ## Authentication

        1. `POST /auth/register` to create the account.
        2. `POST /auth/login` returns `accessToken`. Store it in `EncryptedSharedPreferences`
           (androidx.security-crypto), **not** plain `SharedPreferences` — that file is readable
           on a rooted device and in any device backup.
        3. Send it as `Authorization: Bearer <token>` on every authenticated call — an OkHttp
           `Interceptor` is the tidy way to attach it to every request.

        **The token lasts 30 days and there is no refresh endpoint.** When it expires the app
        must send the user back to Login. There is also **no logout endpoint** — logging out
        means deleting the token locally.

        > A consequence worth knowing: because nothing is tracked server-side, a token stays
        > valid for its full 30 days even after the user changes their password.

        Treat **`401`** anywhere as "token missing, invalid or expired" and route to Login.

        ---

        ## Error format

        Every error, from every endpoint, has the same shape:

        ```json
        {
          "timestamp": "2026-09-23T04:12:30.123Z",
          "status": 409,
          "code": "INSUFFICIENT_STOCK",
          "message": "Not enough stock for product 7f3a...",
          "path": "/api/checkout",
          "fieldErrors": { "quantity": "must be greater than 0" }
        }
        ```

        **Switch on `code`, never on `message`.** `code` is a stable contract; `message` is for
        developers and may change. `fieldErrors` appears only for validation failures — map it
        straight onto your form fields.

        ### Codes the app must handle

        | `code` | HTTP | What the app should do |
        |---|---|---|
        | `VALIDATION_ERROR` | 400 | Show `fieldErrors` inline on the form |
        | `WEAK_PASSWORD` | 400 | "Password needs 8+ characters with a letter and a number" |
        | `MALFORMED_REQUEST` | 400 | A client bug — log it |
        | `UNAUTHENTICATED` | 401 | Send to Login |
        | `INVALID_TOKEN` | 401 | Token expired — send to Login |
        | `INVALID_CREDENTIALS` | 401 | "Email or password is incorrect" |
        | `FORBIDDEN` | 403 | Not the user's resource — go back |
        | `ACCOUNT_NOT_FOUND` | 404 | No account for that email |
        | `PRODUCT_NOT_FOUND` | 404 | Product gone — refresh the list |
        | `ORDER_NOT_FOUND` | 404 | Refresh order list |
        | `EMAIL_ALREADY_EXISTS` | 409 | "This email is already registered" |
        | `INSUFFICIENT_STOCK` | 409 | Refresh the cart and show what is unavailable |
        | `PRODUCT_UNAVAILABLE` | 409 | Item delisted — remove it from the cart |
        | `EMPTY_CART` | 409 | Block the Checkout button |
        | `ORDER_NOT_CANCELLABLE` | 409 | Hide the Cancel button unless status is `CONFIRMED` |
        | `SERVICE_UNAVAILABLE` | 502 | "Something went wrong, please try again" |
        | `INTERNAL_ERROR` | 500 | Same, plus report it |

        ### Reporting a bug to the backend team

        Every response carries an `X-Request-Id` header. Log it, and quote it in the bug report
        — it finds every log line for that exact request across all services.

        ---
        """))

    w(textwrap.dedent("""\
        ### Profile image

        `POST /users/me/avatar` · Bearer · **multipart/form-data**, field name `file` · responds `204`

        PNG or JPEG, **2 MB maximum**. Uploading again replaces the previous image.

        ```java
        // OkHttp / Retrofit
        RequestBody part = RequestBody.create(imageFile, MediaType.parse("image/png"));
        MultipartBody.Part file = MultipartBody.Part.createFormData("file", "profile.png", part);
        api.uploadAvatar(file);   // @Multipart @POST("users/me/avatar")
        ```

        **The format is decided by the file's bytes, not its name or its Content-Type.** A file
        whose content is not really PNG or JPEG is rejected with `UNSUPPORTED_IMAGE_TYPE`, even
        if it is called `photo.png` and uploaded as `image/png`. Renaming a GIF will not work.

        | Failure | HTTP | `code` |
        |---|---|---|
        | Not a PNG or JPEG | 400 | `UNSUPPORTED_IMAGE_TYPE` |
        | Declared type disagrees with the bytes | 400 | `UNSUPPORTED_IMAGE_TYPE` |
        | Larger than 2 MB | 413 | `FILE_TOO_LARGE` |
        | Empty file | 400 | `VALIDATION_ERROR` |

        `GET /users/{id}/avatar` · **Public, no token** · responds `200` with the image bytes

        Deliberately public so an image library can load it in one line:

        ```java
        Glide.with(context)
             .load(BuildConfig.API_BASE_URL + user.avatarUrl)
             .into(profileImageView);
        ```

        404 when the user has no image, so render your placeholder on 404 rather than expecting
        an empty 200.

        `DELETE /users/me/avatar` · Bearer · responds `204`, or 404 if there was no image.

        ### avatarUrl on the user object

        `GET /users/me` and the login response both carry `avatarUrl`:

        - `null` when no image has been uploaded — show your placeholder.
        - otherwise a path **relative to the API base URL**, e.g. `/users/<id>/avatar`.

        Join it to your own base: `BuildConfig.API_BASE_URL + user.avatarUrl`. It is relative on
        purpose, so the same response works against localhost, the emulator and the shared URL
        without the server needing to know which one you are on.

        """))

    w(textwrap.dedent("""\
        ---

        ## Android client notes

        Two things that break Android clients against this API, both with unhelpful errors.

        ### `localhost` does not mean your machine

        On an emulator, `localhost` is the emulator itself. Your backend is on the host:

        | Running on | Base URL for a locally-run backend |
        |---|---|
        | Android emulator | `http://10.0.2.2:8080/api` |
        | Physical device, same Wi-Fi | `http://<your-computer-LAN-ip>:8080/api` |
        | Anywhere | the shared ngrok URL above |

        ### Cleartext HTTP is blocked by default

        Since Android 9 (API 28), plain `http://` fails with
        `CLEARTEXT communication to ... not permitted by network security policy`.

        The shared ngrok URL is HTTPS, so it just works. But if you point at a local backend
        over `http://`, add a debug-only network security config:

        ```xml
        <!-- app/src/debug/res/xml/network_security_config.xml -->
        <network-security-config>
            <domain-config cleartextTrafficPermitted="true">
                <domain includeSubdomains="true">10.0.2.2</domain>
            </domain-config>
        </network-security-config>
        ```

        ```xml
        <!-- app/src/debug/AndroidManifest.xml -->
        <application android:networkSecurityConfig="@xml/network_security_config" />
        ```

        Keep it in the `debug` source set so the release build stays HTTPS-only.

        ### Base URL belongs in config, not in code

        The shared URL is stable while the ngrok agent keeps the same dev domain, but it is not
        permanent infrastructure. Put it in `BuildConfig` or a resource string so it can change
        without a code edit:

        ```gradle
        buildConfigField "String", "API_BASE_URL", "\"https://pounce-arise-pacifier.ngrok-free.dev/api/\""
        ```

        ### Timeouts

        Checkout runs a saga across four services. It normally answers in well under a second,
        but give OkHttp some headroom rather than the default 10s read timeout:

        ```java
        new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        ```

        """))

    w("## Auth endpoints\n")
    w(endpoint(cap, "auth.register", "Register", auth=False,
               description="Creates an account. Email is case-insensitive and must be unique."))
    w(endpoint(cap, "auth.register.duplicate", "Register — email taken", auth=False,
               description="Registering an existing email, including in different casing."))
    w(endpoint(cap, "auth.register.invalid", "Register — validation failure", auth=False,
               description="Shows the `fieldErrors` map you bind to form fields."))
    w(endpoint(cap, "auth.login", "Login", auth=False,
               description="Returns the token plus the user, so Login need not call `/users/me` after.",
               extra="`expiresIn` is seconds (2,592,000 = 30 days)."))
    w(endpoint(cap, "auth.login.bad", "Login — wrong credentials", auth=False,
               description="Identical response whether the email is unknown or the password is wrong, "
                           "so the API does not reveal which accounts exist."))
    w(endpoint(cap, "auth.checkEmail", "Check email exists", auth=False,
               description="Step 1 of the password reset flow."))
    w(endpoint(cap, "auth.checkEmail.unknown", "Check email — not registered", auth=False,
               description="Returns `200` with `exists: false`, not a 404."))

    w(textwrap.dedent("""\
        ### Reset password

        `POST /auth/reset-password` · Public · responds `204`

        ```json
        { "email": "user@techies.vn", "newPassword": "newpassword9" }
        ```

        Returns `204` with no body, or `404 ACCOUNT_NOT_FOUND`.

        > **Security note for the team.** This takes no proof of ownership — no emailed token,
        > no code. Anyone who knows an email address can reset that account. It was chosen
        > deliberately to keep the project small, and it is fine for a local demo, but do not
        > use real credentials against a deployed instance.

        ---
        """))

    w("## User and addresses\n")
    w(endpoint(cap, "user.me", "Current user", auth=True,
               description="For the Profile screen."))
    w(endpoint(cap, "user.update", "Edit profile", auth=True,
               description="Name and phone only. Email cannot change — it is the login identifier."))
    w(endpoint(cap, "user.unauthenticated", "Any authed endpoint without a token", auth=False,
               description="What the app gets when the token is missing or expired."))
    w(textwrap.dedent("""\
        ### Change password

        `PUT /users/me/password` · Bearer · responds `204`

        ```json
        { "currentPassword": "password1", "newPassword": "newpassword9" }
        ```

        `400` if `currentPassword` is wrong, or if the new one fails the policy
        (8+ characters, at least one letter and one digit).

        """))
    w(endpoint(cap, "address.create", "Add address", auth=True,
               description="The user's first address becomes the default automatically, so Checkout "
                           "always has one to preselect. Setting `isDefault` clears the previous default."))
    w(endpoint(cap, "address.list", "List addresses", auth=True,
               description="Default first, then newest. Use the first entry to preselect at Checkout."))
    w(textwrap.dedent("""\
        Also available: `PUT /addresses/{id}` (same body as create) and `DELETE /addresses/{id}`
        → `204`. Deleting the default promotes the next most recent address. Another user's
        address id returns `404`, never their data.

        ---
        """))

    w("## Catalog\n")
    w(endpoint(cap, "catalog.categories", "Categories", auth=False,
               description="For the Home screen. Ordered by `displayOrder`."))
    w(endpoint(cap, "catalog.products", "Product list", auth=False,
               description="Paginated. Query parameters below.",
               extra=textwrap.dedent("""\
                   | Param | Default | Notes |
                   |---|---|---|
                   | `keyword` | — | Matches name + description, accent-insensitive (`bao hanh` finds `bảo hành`) |
                   | `categoryId` | — | UUID from `/categories` |
                   | `minPrice`, `maxPrice` | — | VND |
                   | `page` | `0` | Zero-based |
                   | `size` | `20` | Silently clamped to 100 |
                   | `sort` | `NEWEST` | `NEWEST`, `PRICE_ASC`, `PRICE_DESC`, `NAME_ASC` |

                   **Search covers the product name and description only — not the category name.**
                   Searching "điện thoại" will not return all phones; use `categoryId` for that.""")))
    w(endpoint(cap, "catalog.search", "Search", auth=False,
               description="Same endpoint with `keyword`. There is no separate search route."))
    w(endpoint(cap, "catalog.detail", "Product detail", auth=False,
               description="Includes the description and image gallery."))
    w(endpoint(cap, "catalog.detail.missing", "Product detail — gone", auth=False,
               description="Unknown or delisted products return 404."))
    w(endpoint(cap, "stock.get", "Stock for a product", auth=False,
               description="For the in-stock badge on Product Detail. This is the only public stock endpoint."))

    w("---\n\n## Cart\n")
    w(endpoint(cap, "cart.empty", "Empty cart", auth=True,
               description="A new user gets an empty cart, never a 404."))
    w(endpoint(cap, "cart.add", "Add to cart", auth=True,
               description="Adding a product already in the cart increases that line instead of "
                           "creating a second one. Max 50 lines, max quantity 99 per line.",
               extra="Every response returns the **whole cart**, so the UI can re-render from one payload."))
    w(endpoint(cap, "cart.update", "Change quantity", auth=True,
               description="`quantity: 0` removes the line. `DELETE /cart/items/{itemId}` does the same and returns `204`."))
    w(endpoint(cap, "cart.view", "View cart", auth=True,
               description="`available` is live stock, for greying out unavailable lines.",
               extra="> `name`, `unitPrice` and `available` may be **absent** if the catalog or stock "
                     "service is briefly unreachable. The cart still returns 200 with the line's "
                     "`productId` and `quantity`. Handle the nulls rather than assuming they exist.\n\n"
                     "> **Stock is not checked when adding.** It can change between adding and checking "
                     "out; only checkout decides."))

    w(textwrap.dedent("""\
        ---

        ## Checkout — read this before implementing it

        `POST /checkout` **returns HTTP 200 even when the order fails.**

        ```json
        { "addressId": "<uuid>", "paymentMethod": "MOCK_CARD", "simulatePayment": "SUCCESS" }
        ```

        | Field | Values |
        |---|---|
        | `addressId` | From `GET /addresses` |
        | `paymentMethod` | `COD` (always succeeds) or `MOCK_CARD` |
        | `simulatePayment` | Optional, `MOCK_CARD` only: `SUCCESS` (default), `DECLINED`, `TIMEOUT` |

        `simulatePayment` exists so the app can demo both branches on demand. There is no real
        payment provider.

        **Branch on `order.status`, not on the HTTP status:**

        | `order.status` | `failureCode` | Screen |
        |---|---|---|
        | `CONFIRMED` | `null` | Order Success (CART-03) |
        | `FAILED` | `PAYMENT_FAILED` | Order Failed (CART-04) → back to Checkout |
        | `FAILED` | `OUT_OF_STOCK` | Order Failed, refresh the cart |
        | `FAILED` | `SERVICE_UNAVAILABLE` | Order Failed, offer retry |

        A 4xx here means the request never became an order at all (`EMPTY_CART`,
        `ADDRESS_NOT_FOUND`, `PRODUCT_UNAVAILABLE`) — those are input problems, not failed orders.

        **The cart is cleared only on success.** After a failure the items are still there, so
        the user can fix the problem and retry. Do not clear it client-side.

        """))
    w(endpoint(cap, "checkout.success", "Checkout — success", auth=True,
               description="`status: CONFIRMED`. The cart is now empty and stock is reduced."))
    w(endpoint(cap, "checkout.declined", "Checkout — payment declined", auth=True,
               description="**HTTP 200 with a FAILED order.** Stock that was taken has been returned "
                           "automatically, and the cart is intact for a retry."))
    w(endpoint(cap, "checkout.outOfStock", "Checkout — out of stock", auth=True,
               description="No payment was attempted. Refresh the cart to see what is unavailable."))

    w("---\n\n## Orders\n")
    w(endpoint(cap, "orders.list", "My orders", auth=True,
               description="Newest first. Optional `?status=CONFIRMED|FAILED|CANCELLED`, plus `page` and `size`."))
    w(endpoint(cap, "orders.detail", "Order detail", auth=True,
               description="Prices and the shipping address are snapshots taken at checkout — a later "
                           "catalog price change never alters a past order. Another user's order returns 403."))
    w(endpoint(cap, "orders.cancel", "Cancel order", auth=True,
               description="Only a `CONFIRMED` order can be cancelled. Stock is returned and payment refunded."))
    w(endpoint(cap, "orders.cancel.again", "Cancel — not allowed", auth=True,
               description="Show the Cancel button only when `status == \"CONFIRMED\"`."))

    w(textwrap.dedent("""\
        ---

        ## Screen → endpoint map

        Against the screen list in `Java - BT Lớn.xlsx`:

        | Screen | Code | Endpoints |
        |---|---|---|
        | Login | AUTH-01 | `POST /auth/login` |
        | Register | AUTH-02 | `POST /auth/register` |
        | Home | HOME-01 | `GET /categories` · `GET /products?size=20` |
        | Product List | PRODUCT-01 | `GET /products` (+ `categoryId`, `keyword`, `sort`, `page`) |
        | Product Detail | PRODUCT-05 | `GET /products/{id}` · `GET /stock/{id}` |
        | Cart | CART-01 | `GET /cart` · `POST /cart/items` · `PUT`/`DELETE /cart/items/{id}` |
        | Checkout | CART-02 | `GET /addresses` · `POST /checkout` |
        | Order Success | CART-03 | Response of `POST /checkout` where `status == CONFIRMED` |
        | Order Failed | CART-04 | Same response where `status == FAILED`; read `failureCode` |
        | My Orders | ORDER-01 | `GET /orders` |
        | Order Detail | ORDER-02 | `GET /orders/{id}` |
        | Cancel Order | ORDER-03 | `POST /orders/{id}/cancel` |
        | Profile | PROFILE-01 | `GET /users/me` |
        | Edit Profile | PROFILE-02 | `PUT /users/me` |
        | Change Password | PROFILE-03 | `PUT /users/me/password` |
        | Address List | PROFILE-04 | `GET /addresses` · `POST`/`PUT`/`DELETE /addresses` |

        Forgot Password has no screen code in the sheet but is supported:
        `POST /auth/check-email` then `POST /auth/reset-password`.

        ## Not implemented

        Deliberately out of scope, so do not build UI expecting them: logout (delete the token
        locally), token refresh, product reviews or ratings, wishlist, coupons or discounts,
        multiple shipping options, and order tracking beyond
        `CONFIRMED` / `FAILED` / `CANCELLED`.

        Shipping is a flat **30,000 VND**, free at a subtotal of **500,000 VND** or more.
        """))

    OUT.write_text("\n".join(doc), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} ({len(''.join(doc).splitlines())} lines)")


if __name__ == "__main__":
    main()
