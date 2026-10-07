#!/usr/bin/env python3
"""
Generate a Postman collection from the live API.

    docker compose up -d
    python3 scripts/generate-postman.py

Shares its capture step with generate-api-docs.py, so the collection's saved examples are
real responses and cannot drift from what the API actually returns.

Outputs:
    docs/postman/techies.postman_collection.json
    docs/postman/techies-local.postman_environment.json
    docs/postman/techies-shared.postman_environment.json
"""
import json, os, pathlib, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "docs" / "postman"
TMP = ROOT / ".api-capture-postman.json"

# The public tunnel host is deliberately NOT committed. It is a live entry point to the
# running stack, and a URL in a pushed repo is a URL anyone can find. Export
# TECHIES_PUBLIC_URL before generating to bake the real host into your own copy; without it
# the committed collection carries a placeholder for the reader to fill in.
SHARED_URL = os.environ.get("TECHIES_PUBLIC_URL", "https://your-tunnel.ngrok-free.dev").rstrip("/") + "/api"
LOCAL_URL = "http://localhost:8080/api"

# Saving the token on login is what makes the collection usable without copy-pasting.
SAVE_TOKEN = [
    "const body = pm.response.json();",
    "if (body && body.accessToken) {",
    "    pm.collectionVariables.set('accessToken', body.accessToken);",
    "    pm.collectionVariables.set('userId', body.user.id);",
    "    console.log('accessToken saved to collection variables');",
    "}",
]

def save_var(field, var, from_list=False):
    """Test script that stores an id from the response so later requests can use it."""
    src = "body.content && body.content[0] && body.content[0].id" if from_list else f"body.{field}"
    return [
        "const body = pm.response.json();",
        f"const value = {src};",
        "if (value) {",
        f"    pm.collectionVariables.set('{var}', value);",
        f"    console.log('{var} =', value);",
        "}",
    ]

def url(path):
    clean = path.split("?")[0].lstrip("/")
    segments = [s for s in clean.split("/") if s]
    entry = {"raw": "{{baseUrl}}/" + clean, "host": ["{{baseUrl}}"], "path": segments}
    if "?" in path:
        query = []
        for pair in path.split("?", 1)[1].split("&"):
            k, _, v = pair.partition("=")
            query.append({"key": k, "value": v})
        entry["query"] = query
        entry["raw"] = "{{baseUrl}}/" + clean + "?" + path.split("?", 1)[1]
    return entry

def request(method, path, body=None, auth=True, desc=""):
    headers = [{"key": "Content-Type", "value": "application/json"}]
    # Harmless for API clients, and stops ngrok's free-plan interstitial if Postman is
    # ever configured with a browser User-Agent.
    headers.append({"key": "ngrok-skip-browser-warning", "value": "true"})
    req = {"method": method, "header": headers, "url": url(path), "description": desc}
    if body is not None:
        req["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2, ensure_ascii=False),
                       "options": {"raw": {"language": "json"}}}
    if not auth:
        req["auth"] = {"type": "noauth"}
    return req

def example(name, req, status, payload):
    return {
        "name": name,
        "originalRequest": req,
        "status": {200: "OK", 201: "Created", 204: "No Content", 400: "Bad Request",
                   401: "Unauthorized", 403: "Forbidden", 404: "Not Found",
                   409: "Conflict", 422: "Unprocessable Entity"}.get(status, "Response"),
        "code": status,
        "_postman_previewlanguage": "json",
        "header": [{"key": "Content-Type", "value": "application/json"}],
        "body": json.dumps(payload, indent=2, ensure_ascii=False) if payload is not None else "",
    }



DESC_SIMPLE = (
    "Streams a Vietnamese reply over **Server-Sent Events**.\n\n"
    "Postman shows the raw stream rather than parsed events; for a readable view use "
    "`curl -N`, or the app's EventSource.\n\n"
    "**Skipped during a collection run** unless the variable `RUN_AI` is `true`. Every "
    "request is billed - one per plain question, two for a searching turn and up to four "
    "for a comparison."
)

DESC_SEARCH = (
    "Triggers the model's `search_products` tool.\n\n"
    "Watch the event order: `tool_start` (the model paused to search), then `products` "
    "carrying the cards plus the TRUE total and the query to deep-link the product list "
    "screen, then `token` events as the reply resumes.\n\n"
    "The saved example is a real capture that ended in a quota `error` after the products "
    "were emitted, which is exactly how a mid-stream failure looks to the app."
)


def sse_fixture(name):
    """A real SSE stream recorded from the running stack.

    Committed rather than captured at generation time: the assistant runs on a billed key,
    and regenerating the collection must not spend requests.
    """
    path = ROOT / "scripts" / "fixtures" / name
    return path.read_text(encoding="utf-8") if path.exists() else ""


def sse_example(label, req, body, status=200):
    return {
        "name": label,
        "originalRequest": req,
        "status": "OK",
        "code": status,
        "_postman_previewlanguage": "text",
        "header": [{"key": "Content-Type", "value": "text/event-stream"}],
        "body": body,
    }


# The assistant costs real quota, so it is skipped in a collection run unless the caller
# opts in with RUN_AI=true. Without this, every Newman run would spend three or more of 20
# daily requests - a searching turn costs two and a comparison up to four - and the feature
# would be unusable by the afternoon.
SKIP_UNLESS_OPTED_IN = [
    "const optedIn = String(pm.variables.get('RUN_AI') || '').toLowerCase() === 'true';",
    "if (!optedIn) {",
    "    console.log('Skipping the AI request: set RUN_AI=true to include it.');",
    "    console.log('Every Gemini request is billed; a searching turn costs two, a comparison up to four.');",
    "    if (typeof pm.execution !== 'undefined' && pm.execution.skipRequest) {",
    "        pm.execution.skipRequest();",
    "    }",
    "}",
]


def ensure_cart(product_var="productId", quantity=1):
    """Pre-request script that puts something in the cart.

    A successful checkout clears the cart, so without this every checkout variant after the
    first fails with EMPTY_CART -- which silently hides the declined and out-of-stock
    branches, the two most important flows to demonstrate.
    """
    return [
        "const base = pm.collectionVariables.get('baseUrl');",
        "const token = pm.collectionVariables.get('accessToken');",
        f"const productId = pm.collectionVariables.get('{product_var}');",
        "if (!productId) { console.log('no productId yet - run Catalog > Products first'); }",
        "pm.sendRequest({",
        "    url: base + '/cart/items',",
        "    method: 'POST',",
        "    header: {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token},",
        f"    body: {{mode: 'raw', raw: JSON.stringify({{productId: productId, quantity: {quantity}}})}}",
        "}, (err, res) => console.log('cart primed:', err ? err.message : res.code));",
    ]


def find_zero_stock():
    """Pre-request script that carts a product seeded with zero stock."""
    return [
        "const base = pm.collectionVariables.get('baseUrl');",
        "const token = pm.collectionVariables.get('accessToken');",
        "// 'MSI Modern 14' is seeded with 0 units on purpose - see docs/SEED-IDS.md.",
        "pm.sendRequest(base + '/products?keyword=MSI%20Modern&size=1', (err, res) => {",
        "    if (err) { console.log(err); return; }",
        "    const list = res.json().content;",
        "    if (!list || !list.length) { console.log('zero-stock fixture not found'); return; }",
        "    pm.sendRequest({",
        "        url: base + '/cart/items',",
        "        method: 'POST',",
        "        header: {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token},",
        "        body: {mode: 'raw', raw: JSON.stringify({productId: list[0].id, quantity: 1})}",
        "    }, (e, r) => console.log('zero-stock item carted:', e ? e.message : r.code));",
        "});",
    ]


def item(name, req, examples=None, script=None, pre=None):
    entry = {"name": name, "request": req, "response": examples or []}
    events = []
    if pre:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": pre}})
    if script:
        events.append({"listen": "test", "script": {"type": "text/javascript", "exec": script}})
    if events:
        entry["event"] = events
    return entry

def main():
    env = {**os.environ, "OUT": str(TMP)}
    subprocess.run([sys.executable, str(ROOT / "scripts" / "_api_capture.py")],
                   check=True, cwd=ROOT, env=env)
    cap = json.loads(TMP.read_text(encoding="utf-8"))
    TMP.unlink(missing_ok=True)

    def ex(key, label):
        """Saved example from the capture, if that call was recorded."""
        c = cap.get(key)
        if not c:
            return []
        return [example(label, request(c["method"], c["path"], c["request"]), c["status"], c["response"])]

    collection = {
        "info": {
            "name": "Techies E-Commerce API",
            "description": (
                "Backend for the Techies Android app.\n\n"
                "**Start here:** run *Auth → Login*. Its test script stores `accessToken` as a "
                "collection variable, and every authenticated request uses it automatically — "
                "no copy-pasting.\n\n"
                "Requests are ordered so the whole collection runs top to bottom in the Postman "
                "Runner: listing products stores a `productId`, creating an address stores an "
                "`addressId`, and so on.\n\n"
                "**Checkout returns HTTP 200 even when the order fails** — branch on "
                "`order.status`, not the status code. See docs/API.md.\n\n"
                "The **7. AI assistant** folder is skipped in a run unless `RUN_AI` is `true` - "
                "every Gemini request is billed.\n\n"
                "Saved examples are real captured responses, regenerated by "
                "`python3 scripts/generate-postman.py`."
            ),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]},
        "variable": [
            {"key": "baseUrl", "value": LOCAL_URL},
            {"key": "accessToken", "value": ""},
            {"key": "userId", "value": ""},
            {"key": "productId", "value": ""},
            {"key": "addressId", "value": ""},
            {"key": "cartItemId", "value": ""},
            {"key": "orderId", "value": ""},
            {"key": "email", "value": "demo@techies.vn"},
            {"key": "password", "value": "password1"},
            {"key": "RUN_AI", "value": "false"},
        ],
        "item": [
            {"name": "1. Auth", "item": [
                item("Register", request("POST", "/auth/register", {
                    "email": "{{email}}", "password": "{{password}}",
                    "fullName": "Nguyen Van A", "phone": "0901234567"}, auth=False,
                    desc="Email is unique, case-insensitive. Password needs 8+ chars with a letter and a digit.\n\n**Returns 409 on any run after the first** — the account already exists. That is correct behaviour, and it exercises the duplicate-email path. Change the `email` variable to register a fresh account."),
                    ex("auth.register", "201 Created") + ex("auth.register.duplicate", "409 Email already exists")
                    + ex("auth.register.invalid", "400 Validation error")),
                item("Login", request("POST", "/auth/login", {
                    "email": "{{email}}", "password": "{{password}}"}, auth=False,
                    desc="Run this first. The test script saves accessToken for every other request."),
                    ex("auth.login", "200 OK") + ex("auth.login.bad", "401 Invalid credentials"),
                    SAVE_TOKEN),
                item("Check email exists", request("POST", "/auth/check-email", {"email": "{{email}}"},
                    auth=False, desc="Step 1 of password reset."),
                    ex("auth.checkEmail", "200 exists") + ex("auth.checkEmail.unknown", "200 not registered")),
                item("Reset password", request("POST", "/auth/reset-password", {
                    "email": "{{email}}", "newPassword": "{{password}}"}, auth=False,
                    desc="No token and no email verification by design. Returns 204.\n\n"
                         "Deliberately resets to the SAME password so the collection stays "
                         "re-runnable; change newPassword to test a real reset.")),
            ]},
            {"name": "2. Catalog (public)", "item": [
                item("Categories", request("GET", "/categories", auth=False), ex("catalog.categories", "200 OK")),
                item("Products", request("GET", "/products?page=0&size=20&sort=NEWEST", auth=False,
                    desc="Optional: keyword, categoryId, minPrice, maxPrice. size is clamped to 100."),
                    ex("catalog.products", "200 OK"), save_var("", "productId", from_list=True)),
                item("Search products", request("GET", "/products?keyword=iphone&size=10", auth=False,
                    desc="Accent-insensitive: 'bao hanh' matches 'bảo hành'. Covers name and description only, not category."),
                    ex("catalog.search", "200 OK")),
                item("Product detail", request("GET", "/products/{{productId}}", auth=False),
                    ex("catalog.detail", "200 OK") + ex("catalog.detail.missing", "404 Not found")),
                item("Stock for a product", request("GET", "/stock/{{productId}}", auth=False,
                    desc="For the in-stock badge. The only public stock endpoint."),
                    ex("stock.get", "200 OK")),
            ]},
            {"name": "3. User & addresses", "item": [
                item("Current user", request("GET", "/users/me"),
                     ex("user.me", "200 OK") + ex("user.unauthenticated", "401 No token")),
                item("Update profile", request("PUT", "/users/me",
                    {"fullName": "Nguyen Van B", "phone": "0909999999"},
                    desc="Name and phone only. Email is the login identifier and cannot change."),
                    ex("user.update", "200 OK")),
                item("Change password", request("PUT", "/users/me/password",
                    {"currentPassword": "{{password}}", "newPassword": "{{password}}"},
                    desc="400 if currentPassword is wrong. Returns 204.\n\n"
                         "Sets the same password on purpose so later requests keep working.")),
                item("Upload profile image", {
                    "method": "POST",
                    "header": [{"key": "ngrok-skip-browser-warning", "value": "true"}],
                    "url": url("/users/me/avatar"),
                    "description": ("PNG or JPEG, 2MB max. Pick a file for the `file` form "
                                    "field before sending.\n\nThe format is decided by the "
                                    "file's BYTES, not its name — renaming a GIF to .png is "
                                    "rejected with UNSUPPORTED_IMAGE_TYPE."),
                    "body": {"mode": "formdata", "formdata": [
                        {"key": "file", "type": "file", "src": [],
                         "description": "PNG or JPEG, 2MB maximum"}]},
                }, ex("avatar.upload", "204 No Content")
                   + ex("avatar.rejectDisguised", "400 Not really an image")
                   + ex("avatar.rejectGif", "400 Unsupported format")),
                item("Get profile image", request("GET", "/users/{{userId}}/avatar", auth=False,
                    desc="Public — no token. Returns the image bytes, or 404 when the user has "
                         "no image. Android image libraries load this directly.")),
                item("Delete profile image", request("DELETE", "/users/me/avatar",
                    desc="Returns 204, or 404 when there was no image.")),
                item("List addresses", request("GET", "/addresses",
                    desc="Default first, then newest. Preselect the first at checkout."),
                    ex("address.list", "200 OK")),
                item("Create address", request("POST", "/addresses", {
                    "recipientName": "Nguyen Van B", "phone": "0907654321",
                    "line1": "12 Nguyen Hue", "ward": "Ben Nghe",
                    "district": "Quan 1", "province": "Ho Chi Minh", "isDefault": True},
                    desc="The first address becomes default automatically."),
                    ex("address.create", "201 Created"), save_var("id", "addressId")),
                item("Update address", request("PUT", "/addresses/{{addressId}}", {
                    "recipientName": "Nguyen Van C", "phone": "0907654321",
                    "line1": "99 Le Loi", "ward": "Ben Thanh",
                    "district": "Quan 1", "province": "Ho Chi Minh", "isDefault": True})),
            ]},
            {"name": "4. Cart", "item": [
                item("View cart", request("GET", "/cart",
                    desc="A new user gets an empty cart, never a 404. name/unitPrice/available may be absent if a downstream service is briefly unreachable."),
                    ex("cart.view", "200 OK") + ex("cart.empty", "200 Empty")),
                item("Add item", request("POST", "/cart/items",
                    {"productId": "{{productId}}", "quantity": 2},
                    desc="Re-adding a product increments its line. Max 50 lines, 99 per line. Stock is NOT checked here."),
                    ex("cart.add", "201 Created"), [
                        "const body = pm.response.json();",
                        "if (body.items && body.items.length) {",
                        "    pm.collectionVariables.set('cartItemId', body.items[0].id);",
                        "    console.log('cartItemId =', body.items[0].id);",
                        "}"]),
                item("Update quantity", request("PUT", "/cart/items/{{cartItemId}}", {"quantity": 1},
                    desc="quantity 0 removes the line."), ex("cart.update", "200 OK")),
            ]},
            {"name": "5. Checkout & payment", "item": [
                item("Checkout — awaiting payment", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "MOCK_CARD"},
                    desc="Returns 200 with order.status = AWAITING_PAYMENT. Stock is held and the ordered lines leave the cart; the app now takes the customer to pay and reports the outcome below."),
                    ex("checkout.awaitingPayment", "200 AWAITING_PAYMENT"), save_var("order.id", "orderId"),
                    pre=ensure_cart()),
                item("Payment — success", request("POST", "/orders/{{orderId}}/payment", {
                    "result": "SUCCESS", "transactionRef": "TXN-DEMO-0001"},
                    desc="Confirms the order. The cart was emptied at checkout, so nothing more to clear. Idempotent: sending it again returns the same order."),
                    ex("payment.success", "200 CONFIRMED")),
                item("Checkout — then declined", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "MOCK_CARD"},
                    desc="Place a second order so the declined branch has something to fail."),
                    ex("checkout.declined", "200 AWAITING_PAYMENT"), save_var("order.id", "orderId"),
                    pre=ensure_cart()),
                item("Payment — failed", request("POST", "/orders/{{orderId}}/payment", {
                    "result": "FAILED", "failureReason": "Card declined by issuer"},
                    desc="order.status = FAILED, failureCode = PAYMENT_FAILED. Stock is released and the ordered lines go back into the cart; cartRestore names anything that could not be returned."),
                    ex("payment.failed", "200 FAILED (PAYMENT_FAILED)")),
                item("Checkout — out of stock", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "COD"},
                    desc="failureCode = OUT_OF_STOCK. The order never reaches the payment step."),
                    ex("checkout.outOfStock", "200 FAILED (OUT_OF_STOCK)"), pre=find_zero_stock()),
                item("Checkout — cash on delivery", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "COD"},
                    desc="COD is CONFIRMED straight away: there is nothing to settle before delivery, so it never waits for a payment call."), pre=ensure_cart()),
                item("Checkout — with a coupon", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "COD", "couponCode": "FREESHIP30K"},
                    desc="Fixed amount off the subtotal. Code is case-insensitive and snapshotted onto the order. Seeded codes: TECHIES50K (min 500k), TECHIES500K (min 10tr), FREESHIP30K (no minimum)."),
                    ex("checkout.coupon", "200 with a discount"), save_var("order.id", "orderId"),
                    pre=ensure_cart()),
                item("Checkout — coupon rejected", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "COD", "couponCode": "EXPIRED100K"},
                    desc="409 COUPON_NOT_APPLICABLE, raised before any order exists. PAUSED200K is deactivated; an unknown code is 404 COUPON_NOT_FOUND."),
                    ex("checkout.coupon.rejected", "409 COUPON_NOT_APPLICABLE"), pre=ensure_cart()),
            ]},
            {"name": "7. Reviews", "item": [
                item("Write reviews for an order", request("POST", "/orders/{{orderId}}/reviews",
                    {"reviews": [{"orderItemId": "{{orderItemId}}", "rating": 5,
                                  "comment": "Sản phẩm tốt, giao hàng nhanh."}]},
                    desc="The post-checkout review page submits every line at once. Only a CONFIRMED or COMPLETED order is reviewable, and each line can be reviewed once — buying again earns another. Run 'Order detail' first to capture orderItemId."),
                    ex("reviews.write", "200, lines now reviewed")),
                item("Write reviews — already reviewed", request("POST", "/orders/{{orderId}}/reviews",
                    {"reviews": [{"orderItemId": "{{orderItemId}}", "rating": 1, "comment": "lần hai"}]},
                    desc="409 ALREADY_REVIEWED: one review per order line."),
                    ex("reviews.duplicate", "409 ALREADY_REVIEWED")),
                item("Product reviews", request("GET", "/products/{{productId}}/reviews?page=0&size=10",
                    desc="Served by order-service via the gateway, not catalog: reviews live with the purchases that entitle them. averageRating is 0 when there are none, never null."),
                    ex("reviews.product", "200 OK")),
                item("AI review summary", request("GET", "/products/{{productId}}/review-summary", auth=False,
                    desc="A short AI brief of the reviews, for its own section on the product page. Call it separately and render it late: the first read after a new review waits on a Gemini round trip, every read after that is a local lookup cached on the review count.\n\n**An empty body with HTTP 200 is a normal answer** - fewer than three reviews, or a summary that could not be written and was never cached. Show nothing. This endpoint never returns an error."),
                    ex("reviews.summary", "200 OK"), [
                        "// An empty body is a valid answer here, so do not fail a run on it.",
                        "pm.test('200, with a summary or with nothing', () => pm.response.to.have.status(200));",
                    ]),
                item("AI review summary - nothing to summarise", request("GET", "/products/{{productId}}/review-summary", auth=False,
                    desc="Under three reviews: one review is not a summary and two are not a consensus. 200 with a null body."),
                    ex("reviews.summary.none", "200, null body")),
            ]},

            {"name": "8. Loyalty points & gifts", "item": [
                item("My points and tier", request("GET", "/loyalty/me",
                    desc="**The tier is always a number, 0 to 3, never a name** - naming it is the app's decision, so it can change without a server release. The response carries the whole ladder too, so no threshold needs hardcoding. pointsToNextTier is null at the top."),
                    ex("loyalty.me.tier1", "200, tier 1")),
                item("My points and tier - new account", request("GET", "/loyalty/me",
                    desc="Nothing earned yet. Points arrive at 1 per 1.000d of subtotal minus discount, and only when an order reaches COMPLETED."),
                    ex("loyalty.me.fresh", "200, tier 0")),
                item("My vouchers", request("GET", "/loyalty/vouchers",
                    desc="One voucher per tier reached, ever: 10% at tier 1, 30% at tier 2, 50% at tier 3. Submit the code as couponCode at checkout, exactly like a coupon - the server resolves coupons first and falls through to loyalty, so the app need not know which kind it is."),
                    ex("loyalty.vouchers", "200 OK")),
                item("Gift catalogue", request("GET", "/loyalty/gifts",
                    desc="eligible is the whole claim rule answered in advance - tier, balance, stock and a previous claim - so the app can grey a card out without re-implementing it. inStock and alreadyClaimed are there to explain why."),
                    ex("loyalty.gifts", "200 OK"), [
                        "const body = pm.response.json();",
                        "const claimable = body.find(g => g.eligible);",
                        "if (claimable) { pm.collectionVariables.set('giftId', claimable.id); }",
                    ]),
                item("Claim a gift", request("POST", "/loyalty/gifts/{{giftId}}/claim",
                    desc="Claiming is the whole transaction. It returns a code the customer reads at a counter; the gift moves to the claimed-gifts screen and the code stays readable there indefinitely. **Nothing tracks collection** - there is no status to poll and no further call to make. Run 'Gift catalogue' first to capture giftId."),
                    ex("loyalty.claim", "200, code issued")),
                item("Claim a gift - already claimed", request("POST", "/loyalty/gifts/{{giftId}}/claim",
                    desc="409 GIFT_ALREADY_CLAIMED. One per customer per gift, enforced by a UNIQUE rather than a read-then-write, so a double tap cannot slip through. The other refusals are TIER_TOO_LOW, INSUFFICIENT_POINTS and GIFT_OUT_OF_STOCK."),
                    ex("loyalty.claim.again", "409 GIFT_ALREADY_CLAIMED")),
                item("Claim a gift - tier too low", request("POST", "/loyalty/gifts/{{giftId}}/claim",
                    desc="409 TIER_TOO_LOW, refused before anything is written: no points spent, no stock moved."),
                    ex("loyalty.claim.tierTooLow", "409 TIER_TOO_LOW")),
                item("My claimed gifts", request("GET", "/loyalty/claimed-gifts",
                    desc="Where the customer reads a code back, newest first. Codes never expire."),
                    ex("loyalty.claimedGifts", "200 OK")),
                item("Awarding points is not reachable", request("POST", "/loyalty/points",
                    {"orderRef": "ORD-100001", "userId": "{{userId}}", "amountSpent": 99000000},
                    desc="404 at the edge. POST /loyalty/points and the voucher consume and release endpoints are internal: order-service calls them on the compose network and the gateway routes none of them. So is POST /ai/review-summary."),
                    ex("loyalty.points.notRouted", "404, not routed")),
            ]},
            {"name": "6. Orders", "item": [
                item("My orders", request("GET", "/orders?page=0&size=20",
                    desc="Newest first. Each row carries firstItem (picture + name, no extra fetch) and reviewed. Optional ?status=AWAITING_PAYMENT|CONFIRMED|FAILED|CANCELLED."),
                    ex("orders.list", "200 OK"), [
                        "const body = pm.response.json();",
                        "// Pick a CONFIRMED order: only those are cancellable, and the newest",
                        "// order is often a FAILED one from the checkout-branch demos.",
                        "const target = (body.content || []).find(o => o.status === 'CONFIRMED')",
                        "             || (body.content || [])[0];",
                        "if (target) {",
                        "    pm.collectionVariables.set('orderId', target.id);",
                        "    console.log('orderId =', target.id, '(' + target.status + ')');",
                        "}"]),
                item("Order detail", request("GET", "/orders/{{orderId}}",
                    desc="Prices and address are snapshots taken at checkout. Another user's order returns 403."),
                    ex("orders.detail", "200 OK")),
                item("Update status — mark completed (demo)",
                    request("PUT", "/orders/{{orderId}}/status", {"status": "COMPLETED"},
                    desc="Demo shortcut so the lifecycle can be shown without a management app: nothing else sets COMPLETED. Rewrites the order only and never touches stock, so CANCELLED sent here leaves the stock deducted — use Cancel order for a real one. Payment status follows the new status."),
                    ex("orders.status", "200 OK")),
                item("Update status — PENDING refused",
                    request("PUT", "/orders/{{orderId}}/status", {"status": "PENDING"},
                    desc="PENDING is an internal saga state the app has no screen for, so it is the one status this endpoint will not set."),
                    ex("orders.status.pending", "400 Validation error")),
                item("Cancel order", request("POST", "/orders/{{orderId}}/cancel",
                    desc="Only from CONFIRMED. Stock is returned and payment refunded."),
                    ex("orders.cancel", "200 OK") + ex("orders.cancel.again", "409 Not cancellable")),
            ]},
            {"name": "7. AI assistant", "item": [
                item("Chat — ask about this product",
                     request("POST", "/ai/chat", {
                         "productId": "{{productId}}",
                         "messages": [{"role": "user",
                                       "content": "Sản phẩm này giá bao nhiêu và còn hàng không?"}]},
                         desc=DESC_SIMPLE),
                     [sse_example("200 streamed reply",
                                  request("POST", "/ai/chat", {"productId": "{{productId}}",
                                          "messages": [{"role": "user", "content": "..."}]}),
                                  sse_fixture("ai-chat-simple.sse"))],
                     pre=SKIP_UNLESS_OPTED_IN),
                item("Chat — search for other products",
                     request("POST", "/ai/chat", {
                         "productId": "{{productId}}",
                         "messages": [{"role": "user",
                                       "content": "Tìm giúp mình tai nghe rẻ nhất dưới 3 triệu"}]},
                         desc=DESC_SEARCH),
                     [sse_example("200 tool call, products, then a quota error",
                                  request("POST", "/ai/chat", {"productId": "{{productId}}",
                                          "messages": [{"role": "user", "content": "..."}]}),
                                  sse_fixture("ai-chat-search.sse"))],
                     pre=SKIP_UNLESS_OPTED_IN),
            ]},
            {"name": "9. Destructive (run last)", "item": [
                item("Remove cart item", request("DELETE", "/cart/items/{{cartItemId}}",
                    desc="Returns 204. Kept out of the cart folder so a top-to-bottom run still "
                         "has a cart to check out with. The pre-request script re-primes the "
                         "cart, since checkout empties it."),
                    script=[
                        "const body = pm.response.json ? null : null;",
                    ] and None, pre=[
                        "const base = pm.collectionVariables.get('baseUrl');",
                        "const token = pm.collectionVariables.get('accessToken');",
                        "const productId = pm.collectionVariables.get('productId');",
                        "pm.sendRequest({",
                        "    url: base + '/cart/items',",
                        "    method: 'POST',",
                        "    header: {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token},",
                        "    body: {mode: 'raw', raw: JSON.stringify({productId: productId, quantity: 1})}",
                        "}, (err, res) => {",
                        "    if (!err && res.json().items && res.json().items.length) {",
                        "        pm.collectionVariables.set('cartItemId', res.json().items[0].id);",
                        "    }",
                        "});",
                    ]),
                item("Delete address", request("DELETE", "/addresses/{{addressId}}",
                    desc="Returns 204, and deleting the default promotes the next most recent.\n\n"
                         "Runs last because checkout needs this address to exist.")),
            ]},
        ],
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "techies.postman_collection.json").write_text(
        json.dumps(collection, indent=2, ensure_ascii=False), encoding="utf-8")

    for name, base in (("local", LOCAL_URL), ("shared", SHARED_URL)):
        (OUT_DIR / f"techies-{name}.postman_environment.json").write_text(json.dumps({
            "name": f"Techies — {name}",
            "values": [
                {"key": "baseUrl", "value": base, "enabled": True},
                {"key": "email", "value": "demo@techies.vn", "enabled": True},
                {"key": "password", "value": "password1", "enabled": True},
            ],
            "_postman_variable_scope": "environment",
        }, indent=2), encoding="utf-8")

    folders = len(collection["item"])
    requests = sum(len(f["item"]) for f in collection["item"])
    examples = sum(len(i["response"]) for f in collection["item"] for i in f["item"])
    print(f"collection: {folders} folders, {requests} requests, {examples} saved examples")

if __name__ == "__main__":
    main()
