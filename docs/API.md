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
| `tool_start` | `{"tool": "...", "message": "Đang tìm sản phẩm…"}` | Show a searching indicator |
| `products` | `{"total", "query", "products": [...]}` | Render cards; link "see all" to the product list |
| `done` | `{"finishReason": "stop"}` | Close the stream |
| `error` | `{"code", "message"}` | Show the message inline |

```
event: token
data: {"text":"Dạ, iPhone 15 Pro Max 256GB "}

event: tool_start
data: {"tool":"search_products","message":"Đang tìm sản phẩm…"}

event: products
data: {"total":2,"query":{"keyword":null,"categoryId":"...","maxPrice":3000000,"sort":"PRICE_ASC"},
       "products":[{"id":"...","name":"SoundPEATS Air4 Pro","price":1490000.00,"thumbnailUrl":"..."}]}

event: done
data: {"finishReason":"stop"}
```

### Three things that will catch you out

**`products.total` can exceed the cards shown.** Only 3 are returned, to fit a phone
popup. Render the cards, then a "Xem tất cả {total} sản phẩm" button that opens the
product list screen with `query` applied — that screen already does paging and filters.

**Tapping a card should push a new screen, not replace the current one.** Replacing the
PDP closes the popup and loses the conversation.

**Errors can arrive after a 200.** Once streaming starts the status cannot change, so a
failure becomes an `error` event. Handle both: a non-200 with the usual JSON envelope
*before* streaming, and an `error` event *during* it. `code` is `RATE_LIMITED`,
`SERVICE_UNAVAILABLE`, `PRODUCT_NOT_FOUND` or `INTERNAL_ERROR`.

> **Quota.** The assistant runs on Gemini's free tier: **20 requests per day**, and a
> turn that searches costs two. Expect `RATE_LIMITED` in normal use and make the chat
> button degrade gracefully — nothing else on the product page depends on it.

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
  "email": "fe-demo-f97361@techies.vn",
  "password": "password1",
  "fullName": "Nguyen Van A",
  "phone": "0901234567"
}
```

**Response**

```json
{
  "userId": "a173ff42-99ce-40c3-9a4b-3cfd917ce000",
  "email": "fe-demo-f97361@techies.vn"
}
```


### Register — email taken

`POST /auth/register` · **Public — no token** · responds `409`

Registering an existing email, including in different casing.

**Request**

```json
{
  "email": "fe-demo-f97361@techies.vn",
  "password": "password1",
  "fullName": "Nguyen Van A",
  "phone": "0901234567"
}
```

**Response**

```json
{
  "timestamp": "2026-09-29T08:25:30.619354543Z",
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
  "timestamp": "2026-09-29T08:25:30.627367376Z",
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
  "email": "fe-demo-f97361@techies.vn",
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
    "id": "a173ff42-99ce-40c3-9a4b-3cfd917ce000",
    "email": "fe-demo-f97361@techies.vn",
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
  "email": "fe-demo-f97361@techies.vn",
  "password": "wrongpassword1"
}
```

**Response**

```json
{
  "timestamp": "2026-09-29T08:25:30.799381418Z",
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
  "email": "fe-demo-f97361@techies.vn"
}
```

**Response**

```json
{
  "email": "fe-demo-f97361@techies.vn",
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
  "id": "a173ff42-99ce-40c3-9a4b-3cfd917ce000",
  "email": "fe-demo-f97361@techies.vn",
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
  "id": "a173ff42-99ce-40c3-9a4b-3cfd917ce000",
  "email": "fe-demo-f97361@techies.vn",
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
  "timestamp": "2026-09-29T08:25:30.847816793Z",
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
  "id": "52fa36b6-1d12-4a72-83fd-effe5664271a",
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
    "id": "52fa36b6-1d12-4a72-83fd-effe5664271a",
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
      "id": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
      "name": "MacBook Air M3 13 inch 8GB/256GB",
      "slug": "macbook-air-m3-13",
      "price": 27990000.0,
      "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB",
      "categoryId": "1cf6c2ff-e4cd-50c1-bcb8-39c22a711b9b",
      "categoryName": "Laptop"
    },
    {
      "id": "9dc2c272-0914-54de-b46b-62ca7e689523",
      "name": "vivo V30e 5G",
      "slug": "vivo-v30e",
      "price": 8990000.0,
      "thumbnailUrl": "https://placehold.co/600x600/1e293b/ffffff/png?text=vivo+V30e+5G",
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
      "thumbnailUrl": "https://cdn.tgdd.vn/Products/Images/42/305658/iphone-15-pro-max-blue-thumbnew-600x600.jpg",
      "categoryId": "621a9347-0b52-58d5-ba30-9c524079231d",
      "categoryName": "Điện thoại"
    },
    {
      "id": "e0404394-59b0-5044-af93-6a93adc3af39",
      "name": "iPhone 15 128GB",
      "slug": "iphone-15-128gb",
      "price": 22990000.0,
      "thumbnailUrl": "https://cdn.tgdd.vn/Products/Images/42/281570/iphone-15-xanh-thumb-600x600.jpg",
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

`GET /products/717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a` · **Public — no token** · responds `200`

Includes the description and image gallery.

**Response**

```json
{
  "id": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
  "name": "MacBook Air M3 13 inch 8GB/256GB",
  "slug": "macbook-air-m3-13",
  "description": "MacBook Air M3 13 inch 8GB/256GB - hàng chính hãng, bảo hành 12 tháng tại Techies.",
  "price": 27990000.0,
  "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB",
  "categoryId": "1cf6c2ff-e4cd-50c1-bcb8-39c22a711b9b",
  "categoryName": "Laptop",
  "images": [
    "https://placehold.co/800x800/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB+%281%2F2%29",
    "https://placehold.co/800x800/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB+%282%2F2%29"
  ]
}
```


### Product detail — gone

`GET /products/2d39994e-317d-45d7-a2fa-fa8639a5776a` · **Public — no token** · responds `404`

Unknown or delisted products return 404.

**Response**

```json
{
  "timestamp": "2026-09-29T08:25:30.952235376Z",
  "status": 404,
  "code": "PRODUCT_NOT_FOUND",
  "message": "Product not found",
  "path": "/products/2d39994e-317d-45d7-a2fa-fa8639a5776a"
}
```


### Stock for a product

`GET /stock/717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a` · **Public — no token** · responds `200`

For the in-stock badge on Product Detail. This is the only public stock endpoint.

**Response**

```json
{
  "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
  "available": 51,
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
  "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
  "quantity": 2
}
```

**Response**

```json
{
  "items": [
    {
      "id": "7bc9c0e8-58e8-4d49-b4db-0e2ae55c732b",
      "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
      "name": "MacBook Air M3 13 inch 8GB/256GB",
      "unitPrice": 27990000.0,
      "quantity": 2,
      "lineTotal": 55980000.0,
      "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB",
      "available": 51
    }
  ],
  "subtotal": 55980000.0,
  "itemCount": 1
}
```


### Change quantity

`PUT /cart/items/7bc9c0e8-58e8-4d49-b4db-0e2ae55c732b` · **Bearer token required** · responds `200`

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
      "id": "7bc9c0e8-58e8-4d49-b4db-0e2ae55c732b",
      "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
      "name": "MacBook Air M3 13 inch 8GB/256GB",
      "unitPrice": 27990000.0,
      "quantity": 1,
      "lineTotal": 27990000.0,
      "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB",
      "available": 51
    }
  ],
  "subtotal": 27990000.0,
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
      "id": "7bc9c0e8-58e8-4d49-b4db-0e2ae55c732b",
      "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
      "name": "MacBook Air M3 13 inch 8GB/256GB",
      "unitPrice": 27990000.0,
      "quantity": 1,
      "lineTotal": 27990000.0,
      "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB",
      "available": 51
    }
  ],
  "subtotal": 27990000.0,
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
  "addressId": "52fa36b6-1d12-4a72-83fd-effe5664271a",
  "paymentMethod": "MOCK_CARD"
}
```

**Response**

```json
{
  "order": {
    "id": "25e21865-68a6-4ca1-a3ea-33e9d5da68a4",
    "orderRef": "ORD-20260929-0022",
    "status": "AWAITING_PAYMENT",
    "failureCode": null,
    "subtotal": 27990000.0,
    "shippingFee": 0.0,
    "total": 27990000.0,
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
        "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
        "productName": "MacBook Air M3 13 inch 8GB/256GB",
        "unitPrice": 27990000.0,
        "quantity": 1,
        "lineTotal": 27990000.0,
        "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB"
      }
    ],
    "createdAt": "2026-09-29T08:25:31.117336Z"
  },
  "message": "Order placed, complete the payment to confirm it"
}
```


### Payment — success

`POST /orders/25e21865-68a6-4ca1-a3ea-33e9d5da68a4/payment` · **Bearer token required** · responds `200`

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
    "id": "25e21865-68a6-4ca1-a3ea-33e9d5da68a4",
    "orderRef": "ORD-20260929-0022",
    "status": "CONFIRMED",
    "failureCode": null,
    "subtotal": 27990000.0,
    "shippingFee": 0.0,
    "total": 27990000.0,
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
        "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
        "productName": "MacBook Air M3 13 inch 8GB/256GB",
        "unitPrice": 27990000.0,
        "quantity": 1,
        "lineTotal": 27990000.0,
        "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB"
      }
    ],
    "createdAt": "2026-09-29T08:25:31.117336Z"
  },
  "cartRestore": null
}
```


### Payment — failed

`POST /orders/23552deb-9933-4da2-adf6-a8e5b0caee1b/payment` · **Bearer token required** · responds `200`

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
    "id": "23552deb-9933-4da2-adf6-a8e5b0caee1b",
    "orderRef": "ORD-20260929-0023",
    "status": "FAILED",
    "failureCode": "PAYMENT_FAILED",
    "subtotal": 27990000.0,
    "shippingFee": 0.0,
    "total": 27990000.0,
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
        "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
        "productName": "MacBook Air M3 13 inch 8GB/256GB",
        "unitPrice": 27990000.0,
        "quantity": 1,
        "lineTotal": 27990000.0,
        "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB"
      }
    ],
    "createdAt": "2026-09-29T08:25:31.236435Z"
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
  "addressId": "52fa36b6-1d12-4a72-83fd-effe5664271a",
  "paymentMethod": "COD"
}
```

**Response**

```json
{
  "order": {
    "id": "1e771d69-4324-4172-92c5-736acd2d65a7",
    "orderRef": "ORD-20260929-0024",
    "status": "FAILED",
    "failureCode": "OUT_OF_STOCK",
    "subtotal": 41980000.0,
    "shippingFee": 0.0,
    "total": 41980000.0,
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
        "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
        "productName": "MacBook Air M3 13 inch 8GB/256GB",
        "unitPrice": 27990000.0,
        "quantity": 1,
        "lineTotal": 27990000.0,
        "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB"
      },
      {
        "productId": "b9606e9b-ec51-5d2a-b26e-fd1466cb8bf8",
        "productName": "MSI Modern 14 C13M",
        "unitPrice": 13990000.0,
        "quantity": 1,
        "lineTotal": 13990000.0,
        "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MSI+Modern+14+C13M"
      }
    ],
    "createdAt": "2026-09-29T08:25:31.366625Z"
  },
  "message": "Some items are no longer in stock"
}
```


---

## Orders

### My orders

`GET /orders` · **Bearer token required** · responds `200`

Newest first. Optional `?status=AWAITING_PAYMENT|CONFIRMED|FAILED|CANCELLED`, plus `page` and `size`.

**Response**

```json
{
  "content": [
    {
      "id": "1e771d69-4324-4172-92c5-736acd2d65a7",
      "orderRef": "ORD-20260929-0024",
      "status": "FAILED",
      "failureCode": "OUT_OF_STOCK",
      "total": 41980000.0,
      "itemCount": 2,
      "createdAt": "2026-09-29T08:25:31.366625Z"
    },
    {
      "id": "23552deb-9933-4da2-adf6-a8e5b0caee1b",
      "orderRef": "ORD-20260929-0023",
      "status": "FAILED",
      "failureCode": "PAYMENT_FAILED",
      "total": 27990000.0,
      "itemCount": 1,
      "createdAt": "2026-09-29T08:25:31.236435Z"
    },
    {
      "id": "25e21865-68a6-4ca1-a3ea-33e9d5da68a4",
      "orderRef": "ORD-20260929-0022",
      "status": "CONFIRMED",
      "failureCode": null,
      "total": 27990000.0,
      "itemCount": 1,
      "createdAt": "2026-09-29T08:25:31.117336Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1
}
```


### Order detail

`GET /orders/25e21865-68a6-4ca1-a3ea-33e9d5da68a4` · **Bearer token required** · responds `200`

Prices and the shipping address are snapshots taken at checkout — a later catalog price change never alters a past order. Another user's order returns 403.

**Response**

```json
{
  "id": "25e21865-68a6-4ca1-a3ea-33e9d5da68a4",
  "orderRef": "ORD-20260929-0022",
  "status": "CONFIRMED",
  "failureCode": null,
  "subtotal": 27990000.0,
  "shippingFee": 0.0,
  "total": 27990000.0,
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
      "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
      "productName": "MacBook Air M3 13 inch 8GB/256GB",
      "unitPrice": 27990000.0,
      "quantity": 1,
      "lineTotal": 27990000.0,
      "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB"
    }
  ],
  "createdAt": "2026-09-29T08:25:31.117336Z"
}
```


### Cancel order

`POST /orders/25e21865-68a6-4ca1-a3ea-33e9d5da68a4/cancel` · **Bearer token required** · responds `200`

Only a `CONFIRMED` order can be cancelled. Stock is returned and payment refunded.

**Response**

```json
{
  "id": "25e21865-68a6-4ca1-a3ea-33e9d5da68a4",
  "orderRef": "ORD-20260929-0022",
  "status": "CANCELLED",
  "failureCode": null,
  "subtotal": 27990000.0,
  "shippingFee": 0.0,
  "total": 27990000.0,
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
      "productId": "717f7b5b-1ce7-5c1c-9e7d-3c5818effa1a",
      "productName": "MacBook Air M3 13 inch 8GB/256GB",
      "unitPrice": 27990000.0,
      "quantity": 1,
      "lineTotal": 27990000.0,
      "thumbnailUrl": "https://placehold.co/600x600/312e81/ffffff/png?text=MacBook+Air+M3+13+inch+8GB%2F256GB"
    }
  ],
  "createdAt": "2026-09-29T08:25:31.117336Z"
}
```


### Cancel — not allowed

`POST /orders/25e21865-68a6-4ca1-a3ea-33e9d5da68a4/cancel` · **Bearer token required** · responds `409`

Show the Cancel button only when `status == "CONFIRMED"`.

**Response**

```json
{
  "timestamp": "2026-09-29T08:25:31.451847002Z",
  "status": 409,
  "code": "ORDER_NOT_CANCELLABLE",
  "message": "An order in status CANCELLED cannot be cancelled",
  "path": "/orders/25e21865-68a6-4ca1-a3ea-33e9d5da68a4/cancel"
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
