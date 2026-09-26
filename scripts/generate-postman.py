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

SHARED_URL = "https://pounce-arise-pacifier.ngrok-free.dev/api"
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
            {"name": "5. Checkout", "item": [
                item("Checkout — success", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "MOCK_CARD",
                    "simulatePayment": "SUCCESS"},
                    desc="Returns 200 with order.status = CONFIRMED. Stock is deducted and the cart cleared."),
                    ex("checkout.success", "200 CONFIRMED"), save_var("order.id", "orderId"),
                    pre=ensure_cart()),
                item("Checkout — payment declined", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "MOCK_CARD",
                    "simulatePayment": "DECLINED"},
                    desc="HTTP 200 but order.status = FAILED, failureCode = PAYMENT_FAILED. Stock is restored automatically and the cart is KEPT so the user can retry."),
                    ex("checkout.declined", "200 FAILED (PAYMENT_FAILED)"), pre=ensure_cart()),
                item("Checkout — out of stock", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "COD"},
                    desc="failureCode = OUT_OF_STOCK. No payment is attempted."),
                    ex("checkout.outOfStock", "200 FAILED (OUT_OF_STOCK)"), pre=find_zero_stock()),
                item("Checkout — cash on delivery", request("POST", "/checkout", {
                    "addressId": "{{addressId}}", "paymentMethod": "COD"},
                    desc="COD always approves; simulatePayment is ignored."), pre=ensure_cart()),
            ]},
            {"name": "6. Orders", "item": [
                item("My orders", request("GET", "/orders?page=0&size=20",
                    desc="Newest first. Optional ?status=CONFIRMED|FAILED|CANCELLED."),
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
                item("Cancel order", request("POST", "/orders/{{orderId}}/cancel",
                    desc="Only from CONFIRMED. Stock is returned and payment refunded."),
                    ex("orders.cancel", "200 OK") + ex("orders.cancel.again", "409 Not cancellable")),
            ]},
            {"name": "7. Destructive (run last)", "item": [
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
