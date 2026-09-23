import json, os, urllib.request, urllib.error, uuid, sys

# Override for a deployed stack:  API=http://<host>/api python3 scripts/demo.py
API = os.environ.get("API", "http://localhost:8080/api").rstrip("/")

def call(method, path, body=None, token=None, expect=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=15) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, (json.loads(raw) if raw else None)

def show(label, status, body, *fields):
    bits = ""
    if body and fields:
        bits = "  " + "  ".join(f"{f}={json.dumps(body.get(f), ensure_ascii=False)}" for f in fields if f in body)
    print(f"  [{status}] {label}{bits}")

email = f"demo-{uuid.uuid4().hex[:8]}@techies.vn"
print("=" * 70)
print("1. REGISTER + LOGIN")
s, b = call("POST", "/auth/register", {"email": email, "password": "password1",
                                       "fullName": "Nguyen Van Demo", "phone": "0901234567"})
show("register", s, b, "userId", "email")
s, b = call("POST", "/auth/login", {"email": email, "password": "password1"})
show("login", s, b, "tokenType", "expiresIn")
token = b["accessToken"]

print("\n2. BROWSE")
s, b = call("GET", "/products?keyword=iphone&size=3")
print(f"  [{s}] search 'iphone' -> {b['totalElements']} results")
product = b["content"][0]
print(f"       picked: {product['name']}  {product['price']:,.0f} VND")

print("\n3. ADDRESS")
s, b = call("POST", "/addresses", {"recipientName": "Nguyen Van Demo", "phone": "0901234567",
                                   "line1": "12 Nguyen Hue", "ward": "Ben Nghe",
                                   "district": "Quan 1", "province": "Ho Chi Minh",
                                   "isDefault": True}, token)
show("create address", s, b, "id", "isDefault")
address_id = b["id"]

print("\n4. CART")
s, b = call("POST", "/cart/items", {"productId": product["id"], "quantity": 2}, token)
print(f"  [{s}] add to cart -> subtotal={b['subtotal']:,.0f}  available={b['items'][0]['available']}")

s, stock_before = call("GET", f"/stock/{product['id']}")
print(f"  [{s}] stock BEFORE checkout = {stock_before['available']}")

print("\n5. CHECKOUT - SUCCESS PATH")
s, b = call("POST", "/checkout", {"addressId": address_id, "paymentMethod": "MOCK_CARD",
                                  "simulatePayment": "SUCCESS"}, token)
order = b["order"]
print(f"  [{s}] status={order['status']}  ref={order['orderRef']}  total={order['total']:,.0f}")
print(f"       message: {b['message']}")
s, stock_after = call("GET", f"/stock/{product['id']}")
print(f"  [{s}] stock AFTER success  = {stock_after['available']}  (expected {stock_before['available'] - 2})")
s, cart = call("GET", "/cart", token=token)
print(f"  [{s}] cart after success   = {len(cart['items'])} items (expected 0, cleared)")

print("\n6. CHECKOUT - PAYMENT DECLINED (COMPENSATION PROOF)")
call("POST", "/cart/items", {"productId": product["id"], "quantity": 3}, token)
s, stock_pre = call("GET", f"/stock/{product['id']}")
print(f"  [{s}] stock BEFORE declined checkout = {stock_pre['available']}")

s, b = call("POST", "/checkout", {"addressId": address_id, "paymentMethod": "MOCK_CARD",
                                  "simulatePayment": "DECLINED"}, token)
order2 = b["order"]
print(f"  [{s}] status={order2['status']}  failureCode={order2['failureCode']}")
print(f"       message: {b['message']}")

s, stock_post = call("GET", f"/stock/{product['id']}")
restored = stock_post["available"] == stock_pre["available"]
print(f"  [{s}] stock AFTER declined  = {stock_post['available']}  -> RESTORED: {restored}")
s, cart = call("GET", "/cart", token=token)
print(f"  [{s}] cart after decline    = {len(cart['items'])} items (expected 3 qty kept for retry)")

print("\n7. ORDER HISTORY + CANCEL")
s, b = call("GET", "/orders", token=token)
print(f"  [{s}] my orders = {b['totalElements']}")
for o in b["content"]:
    print(f"       {o['orderRef']}  {o['status']:<10} {o['failureCode'] or '':<16} {o['total']:,.0f}")

s, b = call("POST", f"/orders/{order['id']}/cancel", token=token)
print(f"  [{s}] cancel confirmed order -> status={b['status']} payment={b['paymentStatus']}")
s, stock_cancel = call("GET", f"/stock/{product['id']}")
print(f"  [{s}] stock after cancel = {stock_cancel['available']}")

s, b = call("POST", f"/orders/{order['id']}/cancel", token=token)
print(f"  [{s}] cancel again -> {b.get('code')} (expected ORDER_NOT_CANCELLABLE)")

print("\n8. OUT OF STOCK PATH")
s, b = call("GET", "/products?keyword=MSI&size=5")
if b["totalElements"]:
    zero = b["content"][0]
    s, st = call("GET", f"/stock/{zero['id']}")
    print(f"  [{s}] {zero['name']} stock = {st['available']}")
    call("POST", "/cart/items", {"productId": zero["id"], "quantity": 1}, token)
    s, b = call("POST", "/checkout", {"addressId": address_id, "paymentMethod": "COD"}, token)
    o = b["order"]
    print(f"  [{s}] checkout -> status={o['status']} failureCode={o['failureCode']}")
    print(f"       message: {b['message']}")
print("=" * 70)
