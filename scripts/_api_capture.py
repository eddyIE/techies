"""Exercise every endpoint against the live stack and capture real request/response pairs."""
import json, os, urllib.request, urllib.error, uuid, collections

API = os.environ.get("API", "http://localhost:8080/api")
captured = collections.OrderedDict()

def call(key, method, path, body=None, token=None, note=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=20) as r:
            raw = r.read().decode()
            status, payload = r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        status, payload = e.code, (json.loads(raw) if raw.strip() else None)
    if key:
        captured[key] = {"method": method, "path": path, "request": body,
                         "status": status, "response": payload, "note": note}
    return status, payload

email = f"fe-demo-{uuid.uuid4().hex[:6]}@techies.vn"
PW = "password1"

# --- auth -----------------------------------------------------------------
call("auth.register", "POST", "/auth/register",
     {"email": email, "password": PW, "fullName": "Nguyen Van A", "phone": "0901234567"})
call("auth.register.duplicate", "POST", "/auth/register",
     {"email": email, "password": PW, "fullName": "Nguyen Van A", "phone": "0901234567"})
call("auth.register.invalid", "POST", "/auth/register",
     {"email": "not-an-email", "password": "x", "fullName": "", "phone": "abc"})
_, login = call("auth.login", "POST", "/auth/login", {"email": email, "password": PW})
token = login["accessToken"]
call("auth.login.bad", "POST", "/auth/login", {"email": email, "password": "wrongpassword1"})
call("auth.checkEmail", "POST", "/auth/check-email", {"email": email})
call("auth.checkEmail.unknown", "POST", "/auth/check-email", {"email": "ghost@techies.vn"})

# --- user -----------------------------------------------------------------
call("user.me", "GET", "/users/me", token=token)
call("user.update", "PUT", "/users/me", {"fullName": "Nguyen Van B", "phone": "0909999999"}, token)
call("user.unauthenticated", "GET", "/users/me")

# --- address --------------------------------------------------------------
_, addr = call("address.create", "POST", "/addresses",
    {"recipientName": "Nguyen Van B", "phone": "0907654321", "line1": "12 Nguyen Hue",
     "ward": "Ben Nghe", "district": "Quan 1", "province": "Ho Chi Minh", "isDefault": True}, token)
address_id = addr["id"]
call("address.list", "GET", "/addresses", token=token)

# --- catalog --------------------------------------------------------------
call("catalog.categories", "GET", "/categories")
_, page = call("catalog.products", "GET", "/products?size=2")
product = page["content"][0]
call("catalog.search", "GET", "/products?keyword=iphone&size=2")
call("catalog.detail", "GET", f"/products/{product['id']}")
call("catalog.detail.missing", "GET", f"/products/{uuid.uuid4()}")
call("stock.get", "GET", f"/stock/{product['id']}")

# --- cart -----------------------------------------------------------------
call("cart.empty", "GET", "/cart", token=token)
_, cart = call("cart.add", "POST", "/cart/items", {"productId": product["id"], "quantity": 2}, token)
item_id = cart["items"][0]["id"]
call("cart.update", "PUT", f"/cart/items/{item_id}", {"quantity": 1}, token)
call("cart.view", "GET", "/cart", token=token)

# --- checkout: success ----------------------------------------------------
call("checkout.success", "POST", "/checkout",
     {"addressId": address_id, "paymentMethod": "MOCK_CARD", "simulatePayment": "SUCCESS"}, token,
     note="HTTP 200, order.status = CONFIRMED")

# --- checkout: declined ---------------------------------------------------
call(None, "POST", "/cart/items", {"productId": product["id"], "quantity": 1}, token)
call("checkout.declined", "POST", "/checkout",
     {"addressId": address_id, "paymentMethod": "MOCK_CARD", "simulatePayment": "DECLINED"}, token,
     note="HTTP 200 but order.status = FAILED -- read the body, not the status code")

# --- checkout: out of stock ----------------------------------------------
_, dead = call(None, "GET", "/products?keyword=MSI%20Modern&size=1")
if dead["content"]:
    call(None, "POST", "/cart/items", {"productId": dead["content"][0]["id"], "quantity": 1}, token)
    call("checkout.outOfStock", "POST", "/checkout",
         {"addressId": address_id, "paymentMethod": "COD"}, token,
         note="HTTP 200, order.status = FAILED, failureCode = OUT_OF_STOCK")

# --- orders ---------------------------------------------------------------
_, orders = call("orders.list", "GET", "/orders", token=token)
confirmed = [o for o in orders["content"] if o["status"] == "CONFIRMED"]
if confirmed:
    oid = confirmed[0]["id"]
    call("orders.detail", "GET", f"/orders/{oid}", token=token)
    call("orders.cancel", "POST", f"/orders/{oid}/cancel", token=token)
    call("orders.cancel.again", "POST", f"/orders/{oid}/cancel", token=token,
         note="cancelling twice is refused")

out = os.environ.get("OUT", "captured.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(captured, f, indent=2, ensure_ascii=False)
print(f"captured {len(captured)} endpoint examples")
for k, v in captured.items():
    print(f"  {v['status']:>3}  {v['method']:<6} {v['path'][:52]:<52}  {k}")
