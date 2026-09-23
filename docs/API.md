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
buildConfigField "String", "API_BASE_URL", ""https://pounce-arise-pacifier.ngrok-free.dev/api/""
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


## Auth endpoints

### Register

`POST /auth/register` · **Public — no token** · responds `201`

Creates an account. Email is case-insensitive and must be unique.

**Request**

```json
{
  "email": "fe-demo-3ba734@techies.vn",
  "password": "password1",
  "fullName": "Nguyen Van A",
  "phone": "0901234567"
}
```

**Response**

```json
{
  "userId": "a8231e57-5dad-4b23-89a5-dd7888464a44",
  "email": "fe-demo-3ba734@techies.vn"
}
```


### Register — email taken

`POST /auth/register` · **Public — no token** · responds `409`

Registering an existing email, including in different casing.

**Request**

```json
{
  "email": "fe-demo-3ba734@techies.vn",
  "password": "password1",
  "fullName": "Nguyen Van A",
  "phone": "0901234567"
}
```

**Response**

```json
{
  "timestamp": "2026-09-23T04:40:49.660624877Z",
  "status": 409,
  "code": "EMAIL_ALREADY_EXISTS",
  "message": "An account with this email already exists",
  "path": "/auth/register"
}
```


### Register — validation failure

`POST /auth/register` · **Public — no token** · responds `400`

Shows the `fieldErrors` map you bind to form fields.

**Request**

```json
{
  "email": "not-an-email",
  "password": "x",
  "fullName": "",
  "phone": "abc"
}
```

**Response**

```json
{
  "timestamp": "2026-09-23T04:40:49.668167627Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/auth/register",
  "fieldErrors": {
    "email": "must be a well-formed email address",
    "fullName": "must not be blank",
    "phone": "must be 9-11 digits"
  }
}
```


### Login

`POST /auth/login` · **Public — no token** · responds `200`

Returns the token plus the user, so Login need not call `/users/me` after.

`expiresIn` is seconds (2,592,000 = 30 days).

**Request**

```json
{
  "email": "fe-demo-3ba734@techies.vn",
  "password": "password1"
}
```

**Response**

```json
{
  "accessToken": "<accessToken - a JWT, roughly 300 characters>",
  "tokenType": "Bearer",
  "expiresIn": 2592000,
  "user": {
    "id": "a8231e57-5dad-4b23-89a5-dd7888464a44",
    "email": "fe-demo-3ba734@techies.vn",
    "fullName": "Nguyen Van A",
    "phone": "0901234567"
  }
}
```


### Login — wrong credentials

`POST /auth/login` · **Public — no token** · responds `401`

Identical response whether the email is unknown or the password is wrong, so the API does not reveal which accounts exist.

**Request**

```json
{
  "email": "fe-demo-3ba734@techies.vn",
  "password": "wrongpassword1"
}
```

**Response**

```json
{
  "timestamp": "2026-09-23T04:40:49.854455210Z",
  "status": 401,
  "code": "INVALID_CREDENTIALS",
  "message": "Email or password is incorrect",
  "path": "/auth/login"
}
```


### Check email exists

`POST /auth/check-email` · **Public — no token** · responds `200`

Step 1 of the password reset flow.

**Request**

```json
{
  "email": "fe-demo-3ba734@techies.vn"
}
```

**Response**

```json
{
  "email": "fe-demo-3ba734@techies.vn",
  "exists": true
}
```


### Check email — not registered

`POST /auth/check-email` · **Public — no token** · responds `200`

Returns `200` with `exists: false`, not a 404.

**Request**

```json
{
  "email": "ghost@techies.vn"
}
```

**Response**

```json
{
  "email": "ghost@techies.vn",
  "exists": false
}
```


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

## User and addresses

### Current user

`GET /users/me` · **Bearer token required** · responds `200`

For the Profile screen.

**Response**

```json
{
  "id": "a8231e57-5dad-4b23-89a5-dd7888464a44",
  "email": "fe-demo-3ba734@techies.vn",
  "fullName": "Nguyen Van A",
  "phone": "0901234567"
}
```


### Edit profile

`PUT /users/me` · **Bearer token required** · responds `200`

Name and phone only. Email cannot change — it is the login identifier.

**Request**

```json
{
  "fullName": "Nguyen Van B",
  "phone": "0909999999"
}
```

**Response**

```json
{
  "id": "a8231e57-5dad-4b23-89a5-dd7888464a44",
  "email": "fe-demo-3ba734@techies.vn",
  "fullName": "Nguyen Van B",
  "phone": "0909999999"
}
```


### Any authed endpoint without a token

`GET /users/me` · **Public — no token** · responds `401`

What the app gets when the token is missing or expired.

**Response**

```json
{
  "timestamp": "2026-09-23T04:40:49.886540127Z",
  "status": 401,
  "code": "UNAUTHENTICATED",
  "message": "Authentication required",
  "path": "/api/users/me"
}
```


### Change password

`PUT /users/me/password` · Bearer · responds `204`

```json
{ "currentPassword": "password1", "newPassword": "newpassword9" }
```

`400` if `currentPassword` is wrong, or if the new one fails the policy
(8+ characters, at least one letter and one digit).


### Add address

`POST /addresses` · **Bearer token required** · responds `201`

The user's first address becomes the default automatically, so Checkout always has one to preselect. Setting `isDefault` clears the previous default.

**Request**

```json
{
  "recipientName": "Nguyen Van B",
  "phone": "0907654321",
  "line1": "12 Nguyen Hue",
  "ward": "Ben Nghe",
  "district": "Quan 1",
  "province": "Ho Chi Minh",
  "isDefault": true
}
```

**Response**

```json
{
  "id": "187b94dc-c71e-4e6d-881b-9a45cc221b3b",
  "recipientName": "Nguyen Van B",
  "phone": "0907654321",
  "line1": "12 Nguyen Hue",
  "ward": "Ben Nghe",
  "district": "Quan 1",
  "province": "Ho Chi Minh",
  "isDefault": true
}
```


### List addresses

`GET /addresses` · **Bearer token required** · responds `200`

Default first, then newest. Use the first entry to preselect at Checkout.

**Response**

```json
[
  {
    "id": "187b94dc-c71e-4e6d-881b-9a45cc221b3b",
    "recipientName": "Nguyen Van B",
    "phone": "0907654321",
    "line1": "12 Nguyen Hue",
    "ward": "Ben Nghe",
    "district": "Quan 1",
    "province": "Ho Chi Minh",
    "isDefault": true
  }
]
```


Also available: `PUT /addresses/{id}` (same body as create) and `DELETE /addresses/{id}`
→ `204`. Deleting the default promotes the next most recent address. Another user's
address id returns `404`, never their data.

---

## Catalog

### Categories

`GET /categories` · **Public — no token** · responds `200`

For the Home screen. Ordered by `displayOrder`.

**Response**

```json
[
  {
    "id": "621a9347-0b52-58d5-ba30-9c524079231d",
    "name": "Điện thoại",
    "slug": "dien-thoai",
    "imageUrl": "https://picsum.photos/seed/dien-thoai/400",
    "displayOrder": 1
  },
  {
    "id": "1cf6c2ff-e4cd-50c1-bcb8-39c22a711b9b",
    "name": "Laptop",
    "slug": "laptop",
    "imageUrl": "https://picsum.photos/seed/laptop/400",
    "displayOrder": 2
  },
  {
    "id": "155c3f45-7376-54b8-8fe4-9859afd0cd8e",
    "name": "Máy tính bảng",
    "slug": "tablet",
    "imageUrl": "https://picsum.photos/seed/tablet/400",
    "displayOrder": 3
  },
  {
    "id": "3a8e7aee-9455-52a3-be9a-3790e375821a",
    "name": "Tai nghe",
    "slug": "tai-nghe",
    "imageUrl": "https://picsum.photos/seed/tai-nghe/400",
    "displayOrder": 4
  },
  {
    "id": "5e8b4230-0d61-5f2b-a468-5499e8e7331b",
    "name": "Đồng hồ thông minh",
    "slug": "dong-ho-thong-minh",
    "imageUrl": "https://picsum.photos/seed/dong-ho-thong-minh/400",
    "displayOrder": 5
  },
  {
    "id": "cec6d4cc-9d78-55d8-aaf7-35d3bcf87a9f",
    "name": "Phụ kiện",
    "slug": "phu-kien",
    "imageUrl": "https://picsum.photos/seed/phu-kien/400",
    "displayOrder": 6
  }
]
```


### Product list

`GET /products` · **Public — no token** · responds `200`

Paginated. Query parameters below.

| Param | Default | Notes |
|---|---|---|
| `keyword` | — | Matches name + description, accent-insensitive (`bao hanh` finds `bảo hành`) |
| `categoryId` | — | UUID from `/categories` |
| `minPrice`, `maxPrice` | — | VND |
| `page` | `0` | Zero-based |
| `size` | `20` | Silently clamped to 100 |
| `sort` | `NEWEST` | `NEWEST`, `PRICE_ASC`, `PRICE_DESC`, `NAME_ASC` |

**Search covers the product name and description only — not the category name.**
Searching "điện thoại" will not return all phones; use `categoryId` for that.

**Response**

```json
{
  "content": [
    {
      "id": "e0404394-59b0-5044-af93-6a93adc3af39",
      "name": "iPhone 15 128GB",
      "slug": "iphone-15-128gb",
      "price": 22990000.0,
      "thumbnailUrl": "https://picsum.photos/seed/iphone-15-128gb/600",
      "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
      "categoryName": "Điện thoại"
    },
    {
      "id": "1f8d1d18-6b82-542e-bcca-e7485fe03e0d",
      "name": "iPhone 15 Pro Max 256GB",
      "slug": "iphone-15-pro-max-256gb",
      "price": 31990000.0,
      "thumbnailUrl": "https://picsum.photos/seed/iphone-15-pro-max-256gb/600",
      "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
      "categoryName": "Điện thoại"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 40,
  "totalPages": 20
}
```


### Search

`GET /products` · **Public — no token** · responds `200`

Same endpoint with `keyword`. There is no separate search route.

**Response**

```json
{
  "content": [
    {
      "id": "1f8d1d18-6b82-542e-bcca-e7485fe03e0d",
      "name": "iPhone 15 Pro Max 256GB",
      "slug": "iphone-15-pro-max-256gb",
      "price": 31990000.0,
      "thumbnailUrl": "https://picsum.photos/seed/iphone-15-pro-max-256gb/600",
      "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
      "categoryName": "Điện thoại"
    },
    {
      "id": "e0404394-59b0-5044-af93-6a93adc3af39",
      "name": "iPhone 15 128GB",
      "slug": "iphone-15-128gb",
      "price": 22990000.0,
      "thumbnailUrl": "https://picsum.photos/seed/iphone-15-128gb/600",
      "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
      "categoryName": "Điện thoại"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 2,
  "totalPages": 1
}
```


### Product detail

`GET /products/e0404394-59b0-5044-af93-6a93adc3af39` · **Public — no token** · responds `200`

Includes the description and image gallery.

**Response**

```json
{
  "id": "e0404394-59b0-5044-af93-6a93adc3af39",
  "name": "iPhone 15 128GB",
  "slug": "iphone-15-128gb",
  "description": "iPhone 15 128GB - hàng chính hãng, bảo hành 12 tháng tại Techies.",
  "price": 22990000.0,
  "thumbnailUrl": "https://picsum.photos/seed/iphone-15-128gb/600",
  "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
  "categoryName": "Điện thoại",
  "images": [
    "https://picsum.photos/seed/iphone-15-128gb-1/800",
    "https://picsum.photos/seed/iphone-15-128gb-2/800"
  ]
}
```


### Product detail — gone

`GET /products/bb51560a-06bd-4bb7-af3a-4c6146358e50` · **Public — no token** · responds `404`

Unknown or delisted products return 404.

**Response**

```json
{
  "timestamp": "2026-09-23T04:40:49.943080044Z",
  "status": 404,
  "code": "PRODUCT_NOT_FOUND",
  "message": "Product not found",
  "path": "/products/bb51560a-06bd-4bb7-af3a-4c6146358e50"
}
```


### Stock for a product

`GET /stock/e0404394-59b0-5044-af93-6a93adc3af39` · **Public — no token** · responds `200`

For the in-stock badge on Product Detail. This is the only public stock endpoint.

**Response**

```json
{
  "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
  "available": 90,
  "inStock": true
}
```


---

## Cart

### Empty cart

`GET /cart` · **Bearer token required** · responds `200`

A new user gets an empty cart, never a 404.

**Response**

```json
{
  "items": [],
  "subtotal": 0,
  "itemCount": 0
}
```


### Add to cart

`POST /cart/items` · **Bearer token required** · responds `201`

Adding a product already in the cart increases that line instead of creating a second one. Max 50 lines, max quantity 99 per line.

Every response returns the **whole cart**, so the UI can re-render from one payload.

**Request**

```json
{
  "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
  "quantity": 2
}
```

**Response**

```json
{
  "items": [
    {
      "id": "3974b57a-31b0-4fc3-bb2a-2aae9a6c1806",
      "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
      "name": "iPhone 15 128GB",
      "unitPrice": 22990000.0,
      "quantity": 2,
      "lineTotal": 45980000.0,
      "thumbnailUrl": "https://picsum.photos/seed/iphone-15-128gb/600",
      "available": 90
    }
  ],
  "subtotal": 45980000.0,
  "itemCount": 1
}
```


### Change quantity

`PUT /cart/items/3974b57a-31b0-4fc3-bb2a-2aae9a6c1806` · **Bearer token required** · responds `200`

`quantity: 0` removes the line. `DELETE /cart/items/{itemId}` does the same and returns `204`.

**Request**

```json
{
  "quantity": 1
}
```

**Response**

```json
{
  "items": [
    {
      "id": "3974b57a-31b0-4fc3-bb2a-2aae9a6c1806",
      "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
      "name": "iPhone 15 128GB",
      "unitPrice": 22990000.0,
      "quantity": 1,
      "lineTotal": 22990000.0,
      "thumbnailUrl": "https://picsum.photos/seed/iphone-15-128gb/600",
      "available": 90
    }
  ],
  "subtotal": 22990000.0,
  "itemCount": 1
}
```


### View cart

`GET /cart` · **Bearer token required** · responds `200`

`available` is live stock, for greying out unavailable lines.

> `name`, `unitPrice` and `available` may be **absent** if the catalog or stock service is briefly unreachable. The cart still returns 200 with the line's `productId` and `quantity`. Handle the nulls rather than assuming they exist.

> **Stock is not checked when adding.** It can change between adding and checking out; only checkout decides.

**Response**

```json
{
  "items": [
    {
      "id": "3974b57a-31b0-4fc3-bb2a-2aae9a6c1806",
      "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
      "name": "iPhone 15 128GB",
      "unitPrice": 22990000.0,
      "quantity": 1,
      "lineTotal": 22990000.0,
      "thumbnailUrl": "https://picsum.photos/seed/iphone-15-128gb/600",
      "available": 90
    }
  ],
  "subtotal": 22990000.0,
  "itemCount": 1
}
```


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


### Checkout — success

`POST /checkout` · **Bearer token required** · responds `200`

`status: CONFIRMED`. The cart is now empty and stock is reduced.

**Request**

```json
{
  "addressId": "187b94dc-c71e-4e6d-881b-9a45cc221b3b",
  "paymentMethod": "MOCK_CARD",
  "simulatePayment": "SUCCESS"
}
```

**Response**

```json
{
  "order": {
    "id": "ebc08162-2060-4842-8e1a-f05094ae168c",
    "orderRef": "ORD-20260923-0025",
    "status": "CONFIRMED",
    "failureCode": null,
    "subtotal": 22990000.0,
    "shippingFee": 0.0,
    "total": 22990000.0,
    "paymentMethod": "MOCK_CARD",
    "paymentStatus": "PAID",
    "shippingAddress": {
      "recipientName": "Nguyen Van B",
      "phone": "0907654321",
      "line1": "12 Nguyen Hue",
      "ward": "Ben Nghe",
      "district": "Quan 1",
      "province": "Ho Chi Minh"
    },
    "items": [
      {
        "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
        "productName": "iPhone 15 128GB",
        "unitPrice": 22990000.0,
        "quantity": 1,
        "lineTotal": 22990000.0
      }
    ],
    "createdAt": "2026-09-23T04:40:50.033096Z"
  },
  "message": "Order placed successfully"
}
```


### Checkout — payment declined

`POST /checkout` · **Bearer token required** · responds `200`

**HTTP 200 with a FAILED order.** Stock that was taken has been returned automatically, and the cart is intact for a retry.

**Request**

```json
{
  "addressId": "187b94dc-c71e-4e6d-881b-9a45cc221b3b",
  "paymentMethod": "MOCK_CARD",
  "simulatePayment": "DECLINED"
}
```

**Response**

```json
{
  "order": {
    "id": "19dbe1ba-02df-4e95-8116-2a5f3001bc0e",
    "orderRef": "ORD-20260923-0026",
    "status": "FAILED",
    "failureCode": "PAYMENT_FAILED",
    "subtotal": 22990000.0,
    "shippingFee": 0.0,
    "total": 22990000.0,
    "paymentMethod": "MOCK_CARD",
    "paymentStatus": "DECLINED",
    "shippingAddress": {
      "recipientName": "Nguyen Van B",
      "phone": "0907654321",
      "line1": "12 Nguyen Hue",
      "ward": "Ben Nghe",
      "district": "Quan 1",
      "province": "Ho Chi Minh"
    },
    "items": [
      {
        "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
        "productName": "iPhone 15 128GB",
        "unitPrice": 22990000.0,
        "quantity": 1,
        "lineTotal": 22990000.0
      }
    ],
    "createdAt": "2026-09-23T04:40:50.331811Z"
  },
  "message": "Payment was declined, your cart has been kept"
}
```


### Checkout — out of stock

`POST /checkout` · **Bearer token required** · responds `200`

No payment was attempted. Refresh the cart to see what is unavailable.

**Request**

```json
{
  "addressId": "187b94dc-c71e-4e6d-881b-9a45cc221b3b",
  "paymentMethod": "COD"
}
```

**Response**

```json
{
  "order": {
    "id": "ead0c8cd-3b53-4e9c-87c8-5834979dbb1f",
    "orderRef": "ORD-20260923-0027",
    "status": "FAILED",
    "failureCode": "OUT_OF_STOCK",
    "subtotal": 36980000.0,
    "shippingFee": 0.0,
    "total": 36980000.0,
    "paymentMethod": "COD",
    "paymentStatus": "PENDING",
    "shippingAddress": {
      "recipientName": "Nguyen Van B",
      "phone": "0907654321",
      "line1": "12 Nguyen Hue",
      "ward": "Ben Nghe",
      "district": "Quan 1",
      "province": "Ho Chi Minh"
    },
    "items": [
      {
        "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
        "productName": "iPhone 15 128GB",
        "unitPrice": 22990000.0,
        "quantity": 1,
        "lineTotal": 22990000.0
      },
      {
        "productId": "b9606e9b-ec51-5d2a-b26e-fd1466cb8bf8",
        "productName": "MSI Modern 14 C13M",
        "unitPrice": 13990000.0,
        "quantity": 1,
        "lineTotal": 13990000.0
      }
    ],
    "createdAt": "2026-09-23T04:40:50.650351Z"
  },
  "message": "Some items are no longer in stock"
}
```


---

## Orders

### My orders

`GET /orders` · **Bearer token required** · responds `200`

Newest first. Optional `?status=CONFIRMED|FAILED|CANCELLED`, plus `page` and `size`.

**Response**

```json
{
  "content": [
    {
      "id": "ead0c8cd-3b53-4e9c-87c8-5834979dbb1f",
      "orderRef": "ORD-20260923-0027",
      "status": "FAILED",
      "failureCode": "OUT_OF_STOCK",
      "total": 36980000.0,
      "itemCount": 2,
      "createdAt": "2026-09-23T04:40:50.650351Z"
    },
    {
      "id": "19dbe1ba-02df-4e95-8116-2a5f3001bc0e",
      "orderRef": "ORD-20260923-0026",
      "status": "FAILED",
      "failureCode": "PAYMENT_FAILED",
      "total": 22990000.0,
      "itemCount": 1,
      "createdAt": "2026-09-23T04:40:50.331811Z"
    },
    {
      "id": "ebc08162-2060-4842-8e1a-f05094ae168c",
      "orderRef": "ORD-20260923-0025",
      "status": "CONFIRMED",
      "failureCode": null,
      "total": 22990000.0,
      "itemCount": 1,
      "createdAt": "2026-09-23T04:40:50.033096Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1
}
```


### Order detail

`GET /orders/ebc08162-2060-4842-8e1a-f05094ae168c` · **Bearer token required** · responds `200`

Prices and the shipping address are snapshots taken at checkout — a later catalog price change never alters a past order. Another user's order returns 403.

**Response**

```json
{
  "id": "ebc08162-2060-4842-8e1a-f05094ae168c",
  "orderRef": "ORD-20260923-0025",
  "status": "CONFIRMED",
  "failureCode": null,
  "subtotal": 22990000.0,
  "shippingFee": 0.0,
  "total": 22990000.0,
  "paymentMethod": "MOCK_CARD",
  "paymentStatus": "PAID",
  "shippingAddress": {
    "recipientName": "Nguyen Van B",
    "phone": "0907654321",
    "line1": "12 Nguyen Hue",
    "ward": "Ben Nghe",
    "district": "Quan 1",
    "province": "Ho Chi Minh"
  },
  "items": [
    {
      "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
      "productName": "iPhone 15 128GB",
      "unitPrice": 22990000.0,
      "quantity": 1,
      "lineTotal": 22990000.0
    }
  ],
  "createdAt": "2026-09-23T04:40:50.033096Z"
}
```


### Cancel order

`POST /orders/ebc08162-2060-4842-8e1a-f05094ae168c/cancel` · **Bearer token required** · responds `200`

Only a `CONFIRMED` order can be cancelled. Stock is returned and payment refunded.

**Response**

```json
{
  "id": "ebc08162-2060-4842-8e1a-f05094ae168c",
  "orderRef": "ORD-20260923-0025",
  "status": "CANCELLED",
  "failureCode": null,
  "subtotal": 22990000.0,
  "shippingFee": 0.0,
  "total": 22990000.0,
  "paymentMethod": "MOCK_CARD",
  "paymentStatus": "REFUNDED",
  "shippingAddress": {
    "recipientName": "Nguyen Van B",
    "phone": "0907654321",
    "line1": "12 Nguyen Hue",
    "ward": "Ben Nghe",
    "district": "Quan 1",
    "province": "Ho Chi Minh"
  },
  "items": [
    {
      "productId": "e0404394-59b0-5044-af93-6a93adc3af39",
      "productName": "iPhone 15 128GB",
      "unitPrice": 22990000.0,
      "quantity": 1,
      "lineTotal": 22990000.0
    }
  ],
  "createdAt": "2026-09-23T04:40:50.033096Z"
}
```


### Cancel — not allowed

`POST /orders/ebc08162-2060-4842-8e1a-f05094ae168c/cancel` · **Bearer token required** · responds `409`

Show the Cancel button only when `status == "CONFIRMED"`.

**Response**

```json
{
  "timestamp": "2026-09-23T04:40:50.709935586Z",
  "status": 409,
  "code": "ORDER_NOT_CANCELLABLE",
  "message": "An order in status CANCELLED cannot be cancelled",
  "path": "/orders/ebc08162-2060-4842-8e1a-f05094ae168c/cancel"
}
```


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
