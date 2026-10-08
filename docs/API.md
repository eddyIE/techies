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


---

## AI product assistant (SSE)

`POST /ai/chat` · Bearer · **Server-Sent Events** · `Content-Type: text/event-stream`

```json
{ "productId": "<uuid>",
  "messages": [ {"role": "user", "content": "Sản phẩm này có tốt không?"} ] }
```

Stateless: the app keeps the conversation and resends it each turn, capped at 20
messages and 2000 characters each. Nothing is stored server-side, so closing the popup
discards it. Product facts are fetched from the catalogue by `productId` — the client
never supplies them, so it cannot put invented prices into the prompt.

### Events

| Event | Payload | What the app does |
|---|---|---|
| `token` | `{"text": "..."}` | Append to the reply, in order |
| `tool_start` | `{"tool": "...", "message": "Đang tìm sản phẩm…"}` | Show a searching indicator. Once per turn, however many searches it runs |
| `products` | `{"total", "query", "products": [...]}` | Render cards; link "see all" to the product list. At most one per turn, always after the first `token` |
| `done` | `{"finishReason": "stop"}` | Close the stream |
| `error` | `{"code", "message"}` | Show the message inline |

```
event: token
data: {"text":"Dạ, iPhone 15 Pro Max 256GB "}

event: tool_start
data: {"tool":"search_products","message":"Đang tìm sản phẩm…"}

event: token
data: {"text":"Dạ, em gợi ý vài mẫu phù hợp ạ: "}

event: products
data: {"total":2,"query":{"keyword":null,"categoryId":"...","maxPrice":3000000,"sort":"PRICE_ASC"},
       "products":[{"id":"...","name":"SoundPEATS Air4 Pro","price":1490000.00,"thumbnailUrl":"..."}]}

event: token
data: {"text":"Anh/chị cần em tư vấn thêm gì không ạ?"}

event: done
data: {"finishReason":"stop"}
```

### Five things that will catch you out

**`products.total` equals the number of cards, never more.** At most 5 are returned, to
fit a phone popup. The search usually matches more, but a count above the cards on
screen reads as missing products, so the wider total is not sent. For a "see all"
button, open the product list screen with `query` applied and let that screen state
its own total — it already does paging and filters.

One turn sends **at most one** `products` event even when it searched more than once. A
comparison ("so sánh X với Y") runs two catalogue searches, and their results are merged
into that single event — one card taken from each search in turn, repeats dropped by
product id, then capped at 5. So `total` is still exactly the cards you were sent, and
`query` is the **first** search's — "See all" after a comparison opens one side of it,
which is worth knowing before you label the button.

**`products` always arrives after the first `token`.** Finding the products takes a
whole extra Gemini call that completes before the reply begins, so the cards used to
land while the bubble was still empty. They are now held until the reply has started,
which means the order is `tool_start` → `token` → `products` → more `token`s. Append
events as they come and the bubble reads correctly; do not wait for `done` to render
the cards.

**Tapping a card should push a new screen, not replace the current one.** Replacing the
PDP closes the popup and loses the conversation.

**`done` never follows zero `token` events.** The server guarantees at least one `token`
per turn — if the model produces no text, a short Vietnamese fallback is sent before
`done`. So treat an empty bubble at `done` as a bug rather than a state to handle, and
do not reach for a timeout to decide the reply is never coming. Some turns used to end
with cards and no words at all — that is what this closes.

**Errors can arrive after a 200.** Once streaming starts the status cannot change, so a
failure becomes an `error` event. Handle both: a non-200 with the usual JSON envelope
*before* streaming, and an `error` event *during* it. `code` is `RATE_LIMITED`,
`SERVICE_UNAVAILABLE`, `PRODUCT_NOT_FOUND` or `INTERNAL_ERROR`.

> **Quota.** The assistant runs on a Gemini key with billing enabled, so requests cost
> money and the project's own rate limit still applies — read the current RPM and RPD
> for the model in AI Studio rather than assuming a number. A plain question costs one
> request, a turn that searches costs two, and a comparison costs three or four, which
> is the hard ceiling per turn. Grounding with Google Search is billed on its own
> monthly allowance and does not count against the model's requests at all. Handle
> `RATE_LIMITED` and make the chat button degrade gracefully — nothing else on the
> product page depends on it.

### Android

Retrofit does not do SSE. Use OkHttp's `EventSource`:

```java
EventSources.createFactory(client).newEventSource(request, new EventSourceListener() {
    @Override public void onEvent(EventSource es, String id, String type, String data) {
        switch (type) {
            case "token":      appendToBubble(gson.fromJson(data, TokenEvent.class).text); break;
            case "tool_start": showSearchingIndicator(); break;
            case "products":   renderProductCards(gson.fromJson(data, ProductsEvent.class)); break;
            case "error":      showInlineError(gson.fromJson(data, ErrorEvent.class)); break;
        }
    }
});
```


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
buildConfigField "String", "API_BASE_URL", ""https://your-tunnel.ngrok-free.dev/api/""
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
  "email": "fe-demo-0fc5e5@techies.vn",
  "password": "password1",
  "fullName": "Nguyen Van A",
  "phone": "0901234567"
}
```

**Response**

```json
{
  "userId": "973c5d67-85e5-4da0-b0f3-e7c12178e25e",
  "email": "fe-demo-0fc5e5@techies.vn"
}
```


### Register — email taken

`POST /auth/register` · **Public — no token** · responds `409`

Registering an existing email, including in different casing.

**Request**

```json
{
  "email": "fe-demo-0fc5e5@techies.vn",
  "password": "password1",
  "fullName": "Nguyen Van A",
  "phone": "0901234567"
}
```

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:02.066150045Z",
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
  "timestamp": "2026-10-08T16:11:02.353579587Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/auth/register",
  "fieldErrors": {
    "phone": "must be 9-11 digits",
    "fullName": "must not be blank",
    "email": "must be a well-formed email address"
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
  "email": "fe-demo-0fc5e5@techies.vn",
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
    "id": "973c5d67-85e5-4da0-b0f3-e7c12178e25e",
    "email": "fe-demo-0fc5e5@techies.vn",
    "fullName": "Nguyen Van A",
    "phone": "0901234567",
    "avatarUrl": null
  }
}
```


### Login — wrong credentials

`POST /auth/login` · **Public — no token** · responds `401`

Identical response whether the email is unknown or the password is wrong, so the API does not reveal which accounts exist.

**Request**

```json
{
  "email": "fe-demo-0fc5e5@techies.vn",
  "password": "wrongpassword1"
}
```

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:02.846519879Z",
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
  "email": "fe-demo-0fc5e5@techies.vn"
}
```

**Response**

```json
{
  "email": "fe-demo-0fc5e5@techies.vn",
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
  "id": "973c5d67-85e5-4da0-b0f3-e7c12178e25e",
  "email": "fe-demo-0fc5e5@techies.vn",
  "fullName": "Nguyen Van A",
  "phone": "0901234567",
  "avatarUrl": null
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
  "id": "973c5d67-85e5-4da0-b0f3-e7c12178e25e",
  "email": "fe-demo-0fc5e5@techies.vn",
  "fullName": "Nguyen Van B",
  "phone": "0909999999",
  "avatarUrl": null
}
```


### Any authed endpoint without a token

`GET /users/me` · **Public — no token** · responds `401`

What the app gets when the token is missing or expired.

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:03.086384629Z",
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
  "id": "66762916-2eff-4329-ac2a-e4dd9a3fc5be",
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
    "id": "66762916-2eff-4329-ac2a-e4dd9a3fc5be",
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
    "imageUrl": "https://cdn.tgdd.vn/Products/Images/42/305658/iphone-15-pro-max-blue-thumbnew-600x600.jpg",
    "displayOrder": 1
  },
  {
    "id": "1cf6c2ff-e4cd-50c1-bcb8-39c22a711b9b",
    "name": "Laptop",
    "slug": "laptop",
    "imageUrl": "https://www.apple.com/newsroom/images/2023/10/apple-unveils-new-macbook-pro-featuring-m3-chips/article/Apple-MacBook-Pro-top-view-231030_big.jpg.large_2x.jpg",
    "displayOrder": 2
  },
  {
    "id": "155c3f45-7376-54b8-8fe4-9859afd0cd8e",
    "name": "Máy tính bảng",
    "slug": "tablet",
    "imageUrl": "https://cdn.tgdd.vn/Products/Images/522/325513/ipad-pro-11-inch-m4-wifi-sliver-thumb-600x600.jpg",
    "displayOrder": 3
  },
  {
    "id": "3a8e7aee-9455-52a3-be9a-3790e375821a",
    "name": "Tai nghe",
    "slug": "tai-nghe",
    "imageUrl": "https://images.ctfassets.net/javen7msabdh/7Jo3yt8L1EKkGWPe8gK8tK/ba408004ee0cd7fc35126aba4ead321a/major-v-cream-front-desktop-1.jpeg?w=1200&fm=jpg&q=85",
    "displayOrder": 4
  },
  {
    "id": "5e8b4230-0d61-5f2b-a468-5499e8e7331b",
    "name": "Đồng hồ thông minh",
    "slug": "dong-ho-thong-minh",
    "imageUrl": "https://cdn.tgdd.vn/Products/Images/7077/310858/samsung-galaxy-watch6-classic-47-mm-bac-ksp-600x600.jpg",
    "displayOrder": 5
  },
  {
  ...
}
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
      "id": "759d9034-b058-5375-aa35-cadf437332c3",
      "name": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
      "slug": "smart-tivi-samsung-neo-qled-75qn80f-4k-75-inch-2025",
      "price": 29990000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
      "categoryId": "13df1ea8-f30d-553f-a03c-7af6c0e0d2f4",
      "categoryName": "Tivi"
    },
    {
      "id": "f02ca688-a95d-5ebe-b6d4-04b5f911504f",
      "name": "GIÁ TREO TIVI 32 -75 INCH (C3-FG)",
      "slug": "gia-treo-tivi-north-bayou-c3-fg",
      "price": 350000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/g/i/gia-treo-tivi-north-bayou-c3-fg.png",
      "categoryId": "13df1ea8-f30d-553f-a03c-7af6c0e0d2f4",
      "categoryName": "Tivi"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 63,
  "totalPages": 32
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
      "id": "07324815-82b1-580d-b763-45dbb73655a4",
      "name": "iPhone 16 Pro Max 256GB",
      "slug": "iphone-16-pro-max",
      "price": 30990000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/i/p/iphone-16-pro-max.png",
      "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
      "categoryName": "Điện thoại"
    },
    {
      "id": "1f8d1d18-6b82-542e-bcca-e7485fe03e0d",
      "name": "iPhone 15 Pro Max 256GB",
      "slug": "iphone-15-pro-max-256gb",
      "price": 31990000.0,
      "thumbnailUrl": "https://cdn.tgdd.vn/Products/Images/42/305658/iphone-15-pro-max-blue-thumbnew-600x600.jpg",
      "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
      "categoryName": "Điện thoại"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 3,
  "totalPages": 2
}
```


### Product detail

`GET /products/759d9034-b058-5375-aa35-cadf437332c3` · **Public — no token** · responds `200`

Includes the description and image gallery.

**Response**

```json
{
  "id": "759d9034-b058-5375-aa35-cadf437332c3",
  "name": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
  "slug": "smart-tivi-samsung-neo-qled-75qn80f-4k-75-inch-2025",
  "description": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F) - hàng chính hãng, bảo hành 12 tháng tại Techies.",
  "price": 29990000.0,
  "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
  "categoryId": "13df1ea8-f30d-553f-a03c-7af6c0e0d2f4",
  "categoryName": "Tivi",
  "images": [
    "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png"
  ],
  "specifications": [
    {
      "name": "Kích cỡ màn hình",
      "value": "75 inch"
    },
    {
      "name": "Công nghệ hình ảnh",
      "value": "HDR 10+ / 4K AI Upscaling / Color Booster Pro / Chế độ Filmmaker / Chế độ EyeComfort / Neo Quantum HDR / Wide Viewing Angle / Real Depth Enhancer / Auto HDR Remastering / Supreme UHD Dimming / Motion Xcelerator 144Hz / Quantum Matrix Technology Core / Công nghệ Quantum Matrix Core"
    },
    {
      "name": "Độ phân giải",
      "value": "4K"
    },
    {
      "name": "Loại màn hình",
      "value": "QLED"
    },
    {
      "name": "Tần số quét",
      "value": "100Hz"
    },
    {
  ...
}
```


### Product detail — gone

`GET /products/89b1a19e-0fc3-4930-bdd5-2296267563d4` · **Public — no token** · responds `404`

Unknown or delisted products return 404.

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:03.928606337Z",
  "status": 404,
  "code": "PRODUCT_NOT_FOUND",
  "message": "Product not found",
  "path": "/products/89b1a19e-0fc3-4930-bdd5-2296267563d4"
}
```


### Stock for a product

`GET /stock/759d9034-b058-5375-aa35-cadf437332c3` · **Public — no token** · responds `200`

For the in-stock badge on Product Detail. This is the only public stock endpoint.

**Response**

```json
{
  "productId": "759d9034-b058-5375-aa35-cadf437332c3",
  "available": 36,
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
  "productId": "759d9034-b058-5375-aa35-cadf437332c3",
  "quantity": 2
}
```

**Response**

```json
{
  "items": [
    {
      "id": "92533ee8-2bed-4689-be46-d04c80483560",
      "productId": "759d9034-b058-5375-aa35-cadf437332c3",
      "name": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
      "unitPrice": 29990000.0,
      "quantity": 2,
      "lineTotal": 59980000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
      "available": 36
    }
  ],
  "subtotal": 59980000.0,
  "itemCount": 1
}
```


### Change quantity

`PUT /cart/items/92533ee8-2bed-4689-be46-d04c80483560` · **Bearer token required** · responds `200`

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
      "id": "92533ee8-2bed-4689-be46-d04c80483560",
      "productId": "759d9034-b058-5375-aa35-cadf437332c3",
      "name": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
      "unitPrice": 29990000.0,
      "quantity": 1,
      "lineTotal": 29990000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
      "available": 36
    }
  ],
  "subtotal": 29990000.0,
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
      "id": "92533ee8-2bed-4689-be46-d04c80483560",
      "productId": "759d9034-b058-5375-aa35-cadf437332c3",
      "name": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
      "unitPrice": 29990000.0,
      "quantity": 1,
      "lineTotal": 29990000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
      "available": 36
    }
  ],
  "subtotal": 29990000.0,
  "itemCount": 1
}
```


---

## Checkout — read this before implementing it

**Placing an order and paying for it are two calls.** `POST /checkout` takes the stock
and stops; the app then takes the customer to the payment screen and reports the
outcome to `POST /orders/{id}/payment`. COD is the exception and is confirmed
immediately, because there is nothing to settle before delivery.

```
POST /checkout            -> AWAITING_PAYMENT   stock held, ordered lines REMOVED from the cart
     [customer pays in the app]
POST /orders/{id}/payment -> CONFIRMED           cart already clean
                          -> FAILED              stock released, lines PUT BACK in the cart
```

`POST /checkout` **returns HTTP 200 even when the order fails.**

```json
{ "addressId": "<uuid>", "paymentMethod": "MOCK_CARD" }
```

| Field | Values |
|---|---|
| `addressId` | From `GET /addresses` |
| `couponCode` | Optional discount code, case-insensitive — see below |
| `paymentMethod` | `COD` (confirmed at once) or `MOCK_CARD` (waits for payment) |
| `cartItemIds` | Optional array of cart line ids — see below |

**Buying part of the cart.** For the cart screen's per-line selection, send the ids of
the ticked lines in `cartItemIds`. Only those are bought and only those are removed;
the rest stay in the cart. Omit the field (or send `null`) to buy everything, which is
what the whole-cart Checkout button does.

| Selection | Result |
|---|---|
| omitted / `null` | whole cart |
| `["<id>", ...]` | only those lines |
| `[]` | `400 VALIDATION_ERROR` — asks to buy nothing |
| an id not in the cart | `404 NOT_FOUND` — nothing is bought |

**Branch on `order.status`, not on the HTTP status:**

| `order.status` | `failureCode` | Screen |
|---|---|---|
| `AWAITING_PAYMENT` | `null` | Payment screen — settle, then report the result |
| `CONFIRMED` | `null` | Order Success (CART-03) |
| `FAILED` | `PAYMENT_FAILED` | Order Failed (CART-04) → back to Checkout |
| `FAILED` | `OUT_OF_STOCK` | Order Failed, refresh the cart |
| `FAILED` | `SERVICE_UNAVAILABLE` | Order Failed, offer retry |

A 4xx here means the request never became an order at all (`EMPTY_CART`,
`ADDRESS_NOT_FOUND`, `PRODUCT_UNAVAILABLE`) — those are input problems, not failed orders.

**The cart is emptied of the ordered lines at checkout**, because those goods are now
committed to an order. If the payment then fails — or the window expires — those lines
are put back automatically, so the user still has something to retry with. Do not
clear or refill it client-side; re-read `GET /cart` after a failure.

---

## Discount coupons

Send `couponCode` on checkout. A fixed amount comes off the subtotal, and both the code
and the amount are snapshotted onto the order, so withdrawing a coupon later never
changes what a past order charged.

```
total = subtotal + shippingFee - discount
```

| Code | Effect | Minimum order |
|---|---|---:|
| `TECHIES50K` | −50,000đ | 500,000đ |
| `TECHIES500K` | −500,000đ | 10,000,000đ |
| `FREESHIP30K` | −30,000đ | none |
| `EXPIRED100K` | expired — demonstrates the rejection | none |
| `PAUSED200K` | deactivated — demonstrates the rejection | none |

One code per order; no stacking and no percentages. Rejections happen **before the order
exists**, so an unusable coupon is a mistake to fix on the checkout screen rather than a
failed order in the customer's history:

| Problem | Response |
|---|---|
| unknown code | `404 COUPON_NOT_FOUND` |
| expired or deactivated | `409 COUPON_NOT_APPLICABLE` |
| order below the minimum | `409 COUPON_NOT_APPLICABLE` |

The discount is capped at the subtotal, so an order can never total less than its
shipping fee.

---

## Product reviews

Only a customer who bought the product may review it, and a review belongs to the
**order line** rather than to the product: buying the same thing twice earns two
reviews. That is what lets an order be marked as still needing one.

**After checkout**, take the customer to a review page built from the order's items —
`GET /orders/{id}` returns each line's own `id` and a `reviewed` flag. The page is
skippable: nothing expires, and the order list keeps `reviewed: false` until every line
has been reviewed, so they can return to it from order history at any time.

```json
POST /orders/{orderId}/reviews
{ "reviews": [ { "orderItemId": "<uuid>", "rating": 5, "comment": "Rất tốt" } ] }
```

Several lines in one request, because the review page submits them together. `rating` is
1–5 and required; `comment` is optional, up to 1000 characters. The response is the
order, so the app sees the updated flags without a second call.

| Problem | Response |
|---|---|
| order not paid for, or failed | `409 ORDER_NOT_REVIEWABLE` |
| that line was already reviewed | `409 ALREADY_REVIEWED` |
| line belongs to another order | `404 NOT_FOUND` |
| someone else's order | `403 FORBIDDEN` |

Reviews are immutable — no edit, no delete. The author name is the order's own recipient
name, snapshotted, so a review keeps the name it was written under.

**Reading them**, for the product page:

```
GET /products/{productId}/reviews?page=0&size=10
{ "averageRating": 4.7, "total": 3, "content": [ ... ] }
```

`averageRating` is 0 when nothing has been reviewed, never null. Note this path is served
by **order-service**, not catalog — the reviews live with the purchases that entitle
them. It means a product's rating is not part of the catalog payload: fetch it here.

---

## AI review summary

A short brief of what reviewers say, for its own section on the product page.

```
GET /products/{productId}/review-summary
{ "pros": [...], "cons": [...], "verdict": "...", "reviewCount": 9, "generatedAt": "..." }
```

**Call it separately and render it late.** It is not part of the review list precisely so
the list never waits on it: the first read after a new review pays for a Gemini round trip
and takes several seconds, while every read after that is a local lookup.

**A `null` body is a normal answer**, and HTTP 200. It means either fewer than three
reviews — one review is not a summary and two are not a consensus — or a summary that
could not be written and was never cached. Show nothing in both cases. This endpoint
never returns an error, because the reviews underneath it are the real content.

`pros` and `cons` are short phrases meant to be rendered as chips, not prose.

### Review summary

`GET /products/759d9034-b058-5375-aa35-cadf437332c3/review-summary` · **Public — no token** · responds `200`

Written by ai-service from the reviews order-service holds, then cached against the count it was written from. Note how a complaint several reviewers share survives a high average — a summary that reads as uniformly glowing looks fabricated, which is the failure mode here.

**Response**

```json
{
  "pros": [
    "Chống loá tốt khi xem ban ngày",
    "Hình ảnh sắc nét, màu sắc đẹp",
    "Chơi game 120Hz mượt, độ trễ thấp",
    "Lắp đặt nhanh chóng và cẩn thận"
  ],
  "cons": [
    "Remote ít nút",
    "Điều khiển giọng nói tiếng Việt chưa chuẩn",
    "Giao diện Tizen có quảng cáo"
  ],
  "verdict": "Sản phẩm phù hợp với người thích xem phim, bóng đá và các game thủ chơi game 120Hz.",
  "reviewCount": 8,
  "generatedAt": "2026-10-08T16:09:12.647423Z"
}
```


### Review summary — nothing to summarise

`GET /products/b6f87b01-d775-5c86-b8cf-4833bbdd452f/review-summary` · **Public — no token** · responds `200`

Under three reviews. The body is literally `null` and the status is still 200: show nothing, and do not treat it as an error.

**Response**

```
(no body)
```


---

## Reporting the payment

`POST /orders/{id}/payment` finishes an `AWAITING_PAYMENT` order.

```json
{ "result": "SUCCESS", "transactionRef": "TXN-DEMO-0001" }
```

| Field | Values |
|---|---|
| `result` | `SUCCESS` confirms the order, `FAILED` releases its stock |
| `transactionRef` | The provider's transaction id, up to 64 chars. Send it on success |
| `failureReason` | Optional free text, up to 200 chars, recorded on failure |

A failure returns `cartRestore`, saying what went back:

```json
{ "order": { ... }, "cartRestore": { "linesReturned": 2, "unavailable": ["Sony WH-1000XM5"] } }
```

Restored quantities are **merged and summed** with whatever is in the cart now, so
anything added while paying is kept. A line is listed in `unavailable`, and not
restored, when the product has been delisted or when someone else bought the stock
this order was holding — that customer got there first. Show those names: the cart is
not what it was. `cartRestore` is `null` on success.

**It is idempotent.** Reporting the same result again returns the same order, so a
retry after a dropped connection is safe. Reporting the opposite of a settled order is
`409 ORDER_NOT_PAYABLE` — a paid order is undone with `/cancel`, and a failed one has
already given its stock back. A COD order is `409` too: there is nothing to pay now.

**Do not leave an order awaiting payment.** Stock is held from the moment it is placed, so a
checkout nobody pays for is expired after 15 minutes and its stock returned. Report
`FAILED` as soon as the customer backs out rather than waiting for that sweep.


### Checkout — awaiting payment

`POST /checkout` · **Bearer token required** · responds `200`

`status: AWAITING_PAYMENT`. Stock is held and the ordered lines have left the cart. Take the customer to the payment screen, then report the outcome.

**Request**

```json
{
  "addressId": "66762916-2eff-4329-ac2a-e4dd9a3fc5be",
  "paymentMethod": "MOCK_CARD"
}
```

**Response**

```json
{
  "order": {
    "id": "a9abbd9a-0147-4536-828d-d66fc2c97624",
    "orderRef": "ORD-100093",
    "status": "AWAITING_PAYMENT",
    "failureCode": null,
    "subtotal": 29990000.0,
    "shippingFee": 0.0,
    "total": 29990000.0,
    "couponCode": null,
    "discount": 0.0,
    "paymentMethod": "MOCK_CARD",
    "paymentStatus": "PENDING",
    "paymentRef": null,
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
        "id": "61e4aef7-3817-4f1e-806b-f7b3daccd400",
        "productId": "759d9034-b058-5375-aa35-cadf437332c3",
        "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
        "unitPrice": 29990000.0,
        "quantity": 1,
        "lineTotal": 29990000.0,
        "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
        "reviewed": false
      }
    ],
    "createdAt": "2026-10-08T16:11:05.082659Z"
  },
  "message": "Order placed, complete the payment to confirm it"
}
```


### Payment — success

`POST /orders/a9abbd9a-0147-4536-828d-d66fc2c97624/payment` · **Bearer token required** · responds `200`

`status: CONFIRMED`. The cart is now empty and the stock is sold.

**Request**

```json
{
  "result": "SUCCESS",
  "transactionRef": "TXN-DEMO-0001"
}
```

**Response**

```json
{
  "order": {
    "id": "a9abbd9a-0147-4536-828d-d66fc2c97624",
    "orderRef": "ORD-100093",
    "status": "CONFIRMED",
    "failureCode": null,
    "subtotal": 29990000.0,
    "shippingFee": 0.0,
    "total": 29990000.0,
    "couponCode": null,
    "discount": 0.0,
    "paymentMethod": "MOCK_CARD",
    "paymentStatus": "PAID",
    "paymentRef": "TXN-DEMO-0001",
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
        "id": "61e4aef7-3817-4f1e-806b-f7b3daccd400",
        "productId": "759d9034-b058-5375-aa35-cadf437332c3",
        "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
        "unitPrice": 29990000.0,
        "quantity": 1,
        "lineTotal": 29990000.0,
        "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
        "reviewed": false
      }
    ],
    "createdAt": "2026-10-08T16:11:05.082659Z"
  },
  "cartRestore": null
}
```


### Payment — failed

`POST /orders/f6fe6c11-2751-4da7-9bc2-0f6509f7e5bc/payment` · **Bearer token required** · responds `200`

**HTTP 200 with a FAILED order.** Stock that was held has been released, and the ordered lines are back in the cart. Read `cartRestore` for what could not be returned.

**Request**

```json
{
  "result": "FAILED",
  "failureReason": "Card declined by issuer"
}
```

**Response**

```json
{
  "order": {
    "id": "f6fe6c11-2751-4da7-9bc2-0f6509f7e5bc",
    "orderRef": "ORD-100094",
    "status": "FAILED",
    "failureCode": "PAYMENT_FAILED",
    "subtotal": 29990000.0,
    "shippingFee": 0.0,
    "total": 29990000.0,
    "couponCode": null,
    "discount": 0.0,
    "paymentMethod": "MOCK_CARD",
    "paymentStatus": "DECLINED",
    "paymentRef": null,
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
        "id": "eebb7e15-a396-4fc5-ab32-e85b76392a40",
        "productId": "759d9034-b058-5375-aa35-cadf437332c3",
        "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
        "unitPrice": 29990000.0,
        "quantity": 1,
        "lineTotal": 29990000.0,
        "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
        "reviewed": false
      }
    ],
    "createdAt": "2026-10-08T16:11:07.629905Z"
  },
  "cartRestore": {
    "linesReturned": 1,
    "unavailable": []
  }
}
```


### Checkout — out of stock

`POST /checkout` · **Bearer token required** · responds `200`

The order never reached the payment step. Refresh the cart to see what is unavailable.

**Request**

```json
{
  "addressId": "66762916-2eff-4329-ac2a-e4dd9a3fc5be",
  "paymentMethod": "COD"
}
```

**Response**

```json
{
  "order": {
    "id": "ac6b2689-0cac-4426-ad2a-be7134bc5f85",
    "orderRef": "ORD-100095",
    "status": "FAILED",
    "failureCode": "OUT_OF_STOCK",
    "subtotal": 43980000.0,
    "shippingFee": 0.0,
    "total": 43980000.0,
    "couponCode": null,
    "discount": 0.0,
    "paymentMethod": "COD",
    "paymentStatus": "PENDING",
    "paymentRef": null,
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
        "id": "793bd17f-53a7-4b6f-8ef8-97c38ff29f97",
        "productId": "759d9034-b058-5375-aa35-cadf437332c3",
        "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
        "unitPrice": 29990000.0,
        "quantity": 1,
        "lineTotal": 29990000.0,
        "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
        "reviewed": false
      },
      {
        "id": "9a28e7fe-4cc8-4fc1-aa18-0d6fea8984ed",
        "productId": "b9606e9b-ec51-5d2a-b26e-fd1466cb8bf8",
        "productName": "MSI Modern 14 C13M",
        "unitPrice": 13990000.0,
        "quantity": 1,
        "lineTotal": 13990000.0,
  ...
}
```


---

## Orders

### My orders

`GET /orders` · **Bearer token required** · responds `200`

Newest first. Each row carries `firstItem` (so a row renders a picture and a name without fetching every order) and `reviewed`. Optional `?status=AWAITING_PAYMENT|CONFIRMED|FAILED|CANCELLED`, plus `page` and `size`.

**Response**

```json
{
  "content": [
    {
      "id": "eb1924b0-ef48-47cc-92a8-72730bc75401",
      "orderRef": "ORD-100096",
      "status": "FAILED",
      "failureCode": "OUT_OF_STOCK",
      "total": 73940000.0,
      "itemCount": 2,
      "firstItem": {
        "productId": "759d9034-b058-5375-aa35-cadf437332c3",
        "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
        "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
        "quantity": 2
      },
      "reviewed": false,
      "createdAt": "2026-10-08T16:11:10.630755Z"
    },
    {
      "id": "ac6b2689-0cac-4426-ad2a-be7134bc5f85",
      "orderRef": "ORD-100095",
      "status": "FAILED",
      "failureCode": "OUT_OF_STOCK",
      "total": 43980000.0,
      "itemCount": 2,
      "firstItem": {
        "productId": "759d9034-b058-5375-aa35-cadf437332c3",
        "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
        "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
        "quantity": 1
      },
      "reviewed": false,
      "createdAt": "2026-10-08T16:11:10.041481Z"
    },
    {
      "id": "f6fe6c11-2751-4da7-9bc2-0f6509f7e5bc",
  ...
}
```


### Order detail

`GET /orders/a9abbd9a-0147-4536-828d-d66fc2c97624` · **Bearer token required** · responds `200`

Prices and the shipping address are snapshots taken at checkout — a later catalog price change never alters a past order. Another user's order returns 403.

**Response**

```json
{
  "id": "a9abbd9a-0147-4536-828d-d66fc2c97624",
  "orderRef": "ORD-100093",
  "status": "CONFIRMED",
  "failureCode": null,
  "subtotal": 29990000.0,
  "shippingFee": 0.0,
  "total": 29990000.0,
  "couponCode": null,
  "discount": 0.0,
  "paymentMethod": "MOCK_CARD",
  "paymentStatus": "PAID",
  "paymentRef": "TXN-DEMO-0001",
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
      "id": "61e4aef7-3817-4f1e-806b-f7b3daccd400",
      "productId": "759d9034-b058-5375-aa35-cadf437332c3",
      "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
      "unitPrice": 29990000.0,
      "quantity": 1,
      "lineTotal": 29990000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
      "reviewed": false
    }
  ],
  "createdAt": "2026-10-08T16:11:05.082659Z"
}
```


### Update order status (demo)

`PUT /orders/a9abbd9a-0147-4536-828d-d66fc2c97624/status` · **Bearer token required** · responds `200`

Moves one of your own orders to any status, so the lifecycle can be shown without a management app. Nothing else sets `COMPLETED`: there is no fulfilment process and so no actor to move an order on from `CONFIRMED`.

**This is a demo shortcut, not a workflow.** It rewrites the order only and never touches stock, so sending `CANCELLED` here leaves the stock deducted — a real cancellation is `POST /orders/{id}/cancel`. Payment status follows the new status, so an order moved to `CONFIRMED` reads as `PAID`.

| Value | Effect |
|---|---|
| `AWAITING_PAYMENT` | back to the payment screen, payment status `PENDING` |
| `CONFIRMED` | paid, payment status `PAID` |
| `COMPLETED` | fulfilled; payment status left as it is |
| `FAILED` | failure code `PAYMENT_FAILED`, payment status `DECLINED` |
| `CANCELLED` | payment status `REFUNDED` if it was `PAID` |
| `PENDING` | **refused** — an internal saga state |

**Request**

```json
{
  "status": "COMPLETED"
}
```

**Response**

```json
{
  "id": "a9abbd9a-0147-4536-828d-d66fc2c97624",
  "orderRef": "ORD-100093",
  "status": "COMPLETED",
  "failureCode": null,
  "subtotal": 29990000.0,
  "shippingFee": 0.0,
  "total": 29990000.0,
  "couponCode": null,
  "discount": 0.0,
  "paymentMethod": "MOCK_CARD",
  "paymentStatus": "PAID",
  "paymentRef": "TXN-DEMO-0001",
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
      "id": "61e4aef7-3817-4f1e-806b-f7b3daccd400",
      "productId": "759d9034-b058-5375-aa35-cadf437332c3",
      "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
      "unitPrice": 29990000.0,
      "quantity": 1,
      "lineTotal": 29990000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
      "reviewed": false
    }
  ],
  "createdAt": "2026-10-08T16:11:05.082659Z"
}
```


### Update order status — PENDING refused

`PUT /orders/a9abbd9a-0147-4536-828d-d66fc2c97624/status` · **Bearer token required** · responds `400`

`PENDING` is an internal saga state the app has no screen for, so it is the one status this endpoint will not set.

**Request**

```json
{
  "status": "PENDING"
}
```

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:13.970119176Z",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "PENDING is an internal state and cannot be set",
  "path": "/orders/a9abbd9a-0147-4536-828d-d66fc2c97624/status"
}
```


### Cancel order

`POST /orders/a9abbd9a-0147-4536-828d-d66fc2c97624/cancel` · **Bearer token required** · responds `200`

Only a `CONFIRMED` order can be cancelled. Stock is returned and payment refunded.

**Response**

```json
{
  "id": "a9abbd9a-0147-4536-828d-d66fc2c97624",
  "orderRef": "ORD-100093",
  "status": "CANCELLED",
  "failureCode": null,
  "subtotal": 29990000.0,
  "shippingFee": 0.0,
  "total": 29990000.0,
  "couponCode": null,
  "discount": 0.0,
  "paymentMethod": "MOCK_CARD",
  "paymentStatus": "REFUNDED",
  "paymentRef": "TXN-DEMO-0001",
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
      "id": "61e4aef7-3817-4f1e-806b-f7b3daccd400",
      "productId": "759d9034-b058-5375-aa35-cadf437332c3",
      "productName": "Smart Tivi Samsung Neo QLED 4K 75 inch 2025 (75QN80F)",
      "unitPrice": 29990000.0,
      "quantity": 1,
      "lineTotal": 29990000.0,
      "thumbnailUrl": "https://cdn2.cellphones.com.vn/insecure/rs:fill:0:0/q:90/plain/https://cellphones.com.vn/media/catalog/product/t/v/tv-ss-75qn80f-qled-4k-75_1_.png",
      "reviewed": false
    }
  ],
  "createdAt": "2026-10-08T16:11:05.082659Z"
}
```


### Cancel — not allowed

`POST /orders/a9abbd9a-0147-4536-828d-d66fc2c97624/cancel` · **Bearer token required** · responds `409`

Show the Cancel button only when `status == "CONFIRMED"`.

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:14.396188509Z",
  "status": 409,
  "code": "ORDER_NOT_CANCELLABLE",
  "message": "An order in status CANCELLED cannot be cancelled",
  "path": "/orders/a9abbd9a-0147-4536-828d-d66fc2c97624/cancel"
}
```


---

## Loyalty points and gifts

Points, a three-tier ladder, the voucher each tier grants, and a gift catalogue those
points are spent on.

**The tier is always a number, 0 to 3, and never a name.** Naming it is the app's
decision, so "Đồng / Bạc / Vàng" or anything else can change without a server release.
`GET /loyalty/me` hands over the whole ladder, so no threshold needs hardcoding either.

**Points arrive when an order reaches `COMPLETED`**, at 1 point per 1.000đ of
`subtotal - discount`. Shipping earns nothing, and a half-price coupon earns half the
points. Nothing reaches `COMPLETED` on its own, so a demo has to push each order's
status by hand with `PUT /orders/{id}/status`.

**A tier voucher is submitted as `couponCode` at checkout**, exactly like a coupon. The
server resolves coupons first and falls through to loyalty, so the app does not need to
know which kind of code the customer typed.

**A claimed gift is terminal.** Clicking claim is the whole transaction: it returns a
code, the gift moves to the claimed-gifts screen, and the code stays readable there
indefinitely. Collection happens at a counter and nothing in the app tracks it, so there
is no status to poll and no further call to make.

### Points and tier — new account

`GET /loyalty/me` · **Bearer token required** · responds `200`

Tier 0, nothing earned, and the ladder the app shows progress against. `pointsToNextTier` is null at the top of the ladder.

**Response**

```json
{
  "lifetimePoints": 0,
  "balance": 0,
  "tier": 0,
  "pointsToNextTier": 10000,
  "tiers": [
    {
      "tier": 1,
      "thresholdPoints": 10000,
      "discountPercent": 10
    },
    {
      "tier": 2,
      "thresholdPoints": 30000,
      "discountPercent": 30
    },
    {
      "tier": 3,
      "thresholdPoints": 60000,
      "discountPercent": 50
    }
  ]
}
```


### Completing an order (demo) credits its points

`PUT /orders/0c9b3e2f-8d88-42f1-8bfb-53ab9a54370d/status` · **Bearer token required** · responds `200`

The only thing that awards points. Re-sending `COMPLETED` credits nothing: the award happens on the transition, and loyalty dedupes on the order reference even if it did.

**Request**

```json
{
  "status": "COMPLETED"
}
```

**Response**

```json
{
  "id": "0c9b3e2f-8d88-42f1-8bfb-53ab9a54370d",
  "orderRef": "ORD-100098",
  "status": "COMPLETED",
  "failureCode": null,
  "subtotal": 10000000.0,
  "shippingFee": 0.0,
  "total": 10000000.0,
  "couponCode": null,
  "discount": 0.0,
  "paymentMethod": "COD",
  "paymentStatus": "PAID",
  "paymentRef": null,
  "shippingAddress": {
    "recipientName": "Tran Thi Loyal",
    "phone": "0902222222",
    "line1": "45 Nguyen Trai",
    "ward": "Ben Thanh",
    "district": "Quan 1",
    "province": "Ho Chi Minh"
  },
  "items": [
    {
      "id": "ffcd9dac-0753-4ffc-995b-62c4e46c3972",
      "productId": "9c57e463-c2ec-560b-b0ca-c84f6a7a56e7",
      "productName": "Demo A - 10 triệu",
      "unitPrice": 10000000.0,
      "quantity": 1,
      "lineTotal": 10000000.0,
      "thumbnailUrl": "https://picsum.photos/seed/demo-tier-a/600",
      "reviewed": false
    }
  ],
  "createdAt": "2026-10-08T16:11:16.488054Z"
}
```


### Points and tier — after one order

`GET /loyalty/me` · **Bearer token required** · responds `200`

10.000.000đ of goods earned 10.000 points, which is tier 1.

**Response**

```json
{
  "lifetimePoints": 10000,
  "balance": 10000,
  "tier": 1,
  "pointsToNextTier": 20000,
  "tiers": [
    {
      "tier": 1,
      "thresholdPoints": 10000,
      "discountPercent": 10
    },
    {
      "tier": 2,
      "thresholdPoints": 30000,
      "discountPercent": 30
    },
    {
      "tier": 3,
      "thresholdPoints": 60000,
      "discountPercent": 50
    }
  ]
}
```


### My vouchers

`GET /loyalty/vouchers` · **Bearer token required** · responds `200`

One voucher per tier reached, ever. `consumedAt` is null until it is spent; a cancelled order returns it to null.

**Response**

```json
[
  {
    "code": "TIER1-JPCVQWRT",
    "tier": 1,
    "discountPercent": 10,
    "issuedAt": "2026-10-08T16:11:17.016895Z",
    "expiresAt": null,
    "consumedAt": null
  }
]
```


### Gift catalogue

`GET /loyalty/gifts` · **Bearer token required** · responds `200`

`eligible` is the whole claim rule answered in advance — tier, balance, stock and a previous claim — so the app can grey a card out without re-implementing it. `inStock` and `alreadyClaimed` are there to explain why.

**Response**

```json
[
  {
    "id": "ce2eb2ca-a432-5d29-9755-1f4f1abf2a14",
    "name": "Ốp lưng silicon",
    "description": "Ốp lưng silicon chống sốc, nhận tại cửa hàng ElecGo gần nhất.",
    "imageUrl": "https://picsum.photos/seed/op-lung-silicon/400",
    "pointsCost": 500,
    "minTier": 0,
    "inStock": true,
    "eligible": true,
    "alreadyClaimed": false
  },
  {
    "id": "fa31e0b6-78d5-5739-a025-965512251dd0",
    "name": "Cáp sạc USB-C 1m",
    "description": "Cáp sạc USB-C dài 1m, hỗ trợ sạc nhanh 20W.",
    "imageUrl": "https://picsum.photos/seed/cap-usb-c/400",
    "pointsCost": 800,
    "minTier": 0,
    "inStock": true,
    "eligible": true,
    "alreadyClaimed": false
  },
  {
    "id": "e20b380a-f62c-56eb-9ba4-f7cbf404ee5c",
    "name": "Pin sạc dự phòng 10.000mAh",
    "description": "Pin sạc dự phòng 10.000mAh, hai cổng ra. Tạm thời hết hàng.",
    "imageUrl": "https://picsum.photos/seed/pin-du-phong/400",
    "pointsCost": 1500,
    "minTier": 0,
    "inStock": false,
    "eligible": false,
    "alreadyClaimed": false
  },
  {
    "id": "1d61e4c8-962b-55bd-b0c9-f6c1045571ab",
    "name": "Tai nghe có dây",
    "description": "Tai nghe nhét tai có dây kèm micro, jack 3.5mm.",
    "imageUrl": "https://picsum.photos/seed/tai-nghe-day/400",
    "pointsCost": 2000,
    "minTier": 1,
    "inStock": true,
    "eligible": true,
    "alreadyClaimed": false
  },
  {
  ...
}
```


### Claim a gift

`POST /loyalty/gifts/ce2eb2ca-a432-5d29-9755-1f4f1abf2a14/claim` · **Bearer token required** · responds `200`

Spends the points and returns the code. Claiming never changes the lifetime total, so it cannot cost a customer their tier.

**Response**

```json
{
  "redemptionId": "2cb61ab2-1650-438b-9448-5e1e6b9dd98f",
  "code": "GIFT-X9GZ-SBX6",
  "giftName": "Ốp lưng silicon",
  "pointsSpent": 500,
  "balanceAfter": 9500,
  "claimedAt": "2026-10-08T16:11:17.457949969Z"
}
```


### Claim — already claimed

`POST /loyalty/gifts/ce2eb2ca-a432-5d29-9755-1f4f1abf2a14/claim` · **Bearer token required** · responds `409`

One per customer per gift, enforced by a UNIQUE rather than a read-then-write, so a double tap cannot slip through. The other refusals are `TIER_TOO_LOW`, `INSUFFICIENT_POINTS` and `GIFT_OUT_OF_STOCK`.

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:17.592978177Z",
  "status": 409,
  "code": "GIFT_ALREADY_CLAIMED",
  "message": "User 9236af2a-f23c-477e-835b-d8ce4f0f24ce already claimed gift ce2eb2ca-a432-5d29-9755-1f4f1abf2a14",
  "path": "/loyalty/gifts/ce2eb2ca-a432-5d29-9755-1f4f1abf2a14/claim"
}
```


### Claim — tier too low

`POST /loyalty/gifts/5a895aa7-839d-5f07-9729-7f3a68806ac1/claim` · **Bearer token required** · responds `409`

Refused before anything is written: no points spent, no stock moved.

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:17.695430261Z",
  "status": 409,
  "code": "TIER_TOO_LOW",
  "message": "Gift 5a895aa7-839d-5f07-9729-7f3a68806ac1 needs tier 3, customer is tier 1",
  "path": "/loyalty/gifts/5a895aa7-839d-5f07-9729-7f3a68806ac1/claim"
}
```


### My claimed gifts

`GET /loyalty/claimed-gifts` · **Bearer token required** · responds `200`

Where the customer reads the code back. Newest first, and never expires.

**Response**

```json
[
  {
    "redemptionId": "2cb61ab2-1650-438b-9448-5e1e6b9dd98f",
    "code": "GIFT-X9GZ-SBX6",
    "giftName": "Ốp lưng silicon",
    "imageUrl": "https://picsum.photos/seed/op-lung-silicon/400",
    "pointsSpent": 500,
    "claimedAt": "2026-10-08T16:11:17.457950Z"
  }
]
```


### Awarding points is not reachable

`POST /loyalty/points` · **Bearer token required** · responds `404`

`POST /loyalty/points` and the voucher consume and release endpoints are internal: order-service calls them on the compose network and the gateway routes none of them. So is `POST /ai/review-summary`.

**Request**

```json
{
  "orderRef": "ORD-100001",
  "userId": "7c8a5bce-5ea2-43a7-ab71-b5355d8c5618",
  "amountSpent": 99000000
}
```

**Response**

```json
{
  "timestamp": "2026-10-08T16:11:17.919+00:00",
  "path": "/api/loyalty/points",
  "status": 404,
  "error": "Not Found",
  "requestId": "f0c86c51-12599"
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
| _(demo, no screen)_ | — | `PUT /orders/{id}/status` |
| Profile | PROFILE-01 | `GET /users/me` |
| Edit Profile | PROFILE-02 | `PUT /users/me` |
| Change Password | PROFILE-03 | `PUT /users/me/password` |
| Address List | PROFILE-04 | `GET /addresses` · `POST`/`PUT`/`DELETE /addresses` |
| _(new)_ Loyalty | — | `GET /loyalty/me` · `GET /loyalty/vouchers` |
| _(new)_ Gift exchange | — | `GET /loyalty/gifts` · `POST /loyalty/gifts/{id}/claim` |
| _(new)_ Claimed gifts | — | `GET /loyalty/claimed-gifts` |
| Product Detail | PRODUCT-05 | also `GET /products/{id}/review-summary` |

Forgot Password has no screen code in the sheet but is supported:
`POST /auth/check-email` then `POST /auth/reset-password`.

## Not implemented

Deliberately out of scope, so do not build UI expecting them: logout (delete the token
locally), token refresh, wishlist, multiple shipping options, and fulfilment tracking
(there is no `SHIPPED` or `DELIVERED`, and `COMPLETED` is only reachable through the
demo status endpoint).

Gifts are not delivered and their collection is not tracked: a claim returns a code and
the app is never told what happens to it. Points do not convert back into a discount
either — they buy gifts, and tiers grant vouchers. See `docs/EXTENSIONS.md`.

Shipping is a flat **30,000 VND**, free at a subtotal of **500,000 VND** or more.
