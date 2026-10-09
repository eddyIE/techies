"""Exercise every endpoint against the live stack and capture real request/response pairs."""
import json, os, subprocess, urllib.request, urllib.error, uuid, collections

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

# Standing in for an inbox. Registration now mails a 6-digit code, and only its BCrypt hash is
# stored, so there is nothing for this script to read back: it plants the hash of a known code
# instead. This is the one place the capture reaches past the API, and the alternative is
# leaving the two verification endpoints out of the documents the mobile client is built from.
CODE = "123456"
CODE_HASH = "$2y$10$ei267uHweJ7Gi7qsdbBZtOzass7a03UPPPBrKGrHUx4dSXkrEzwNO"   # BCrypt("123456")

def plant_code(address):
    """Makes CODE the live code for that address, and clears any failed attempts."""
    subprocess.run(
        ["docker", "exec", "techies-postgres", "psql", "-U", "techies", "-d", "techies", "-q",
         "-c", "UPDATE identity.verification_codes SET code_hash = '%s', attempts = 0 "
               "WHERE user_id = (SELECT id FROM identity.users WHERE LOWER(email) = '%s')"
               % (CODE_HASH, address.lower())],
        check=True, capture_output=True)
    return CODE

def register_verified(address, full_name, phone):
    """Registers, verifies, and returns the token. Login refuses an unverified account."""
    call(None, "POST", "/auth/register",
         {"email": address, "password": PW, "fullName": full_name, "phone": phone})
    plant_code(address)
    _, verified = call(None, "POST", "/auth/verify-email",
                       {"email": address, "code": CODE})
    return verified["accessToken"]

# --- auth -----------------------------------------------------------------
call("auth.register", "POST", "/auth/register",
     {"email": email, "password": PW, "fullName": "Nguyen Van A", "phone": "0901234567"})
call("auth.register.duplicate", "POST", "/auth/register",
     {"email": email, "password": PW, "fullName": "Nguyen Van A", "phone": "0901234567"})
call("auth.register.invalid", "POST", "/auth/register",
     {"email": "not-an-email", "password": "x", "fullName": "", "phone": "abc"})
call("auth.login.unverified", "POST", "/auth/login", {"email": email, "password": PW},
     note="the password is correct -- the account just has not confirmed its address yet")
call("auth.resendOtp.tooSoon", "POST", "/auth/resend-otp",
     {"email": email, "purpose": "REGISTRATION"},
     note="the code from registration is seconds old, so a second one is refused")
plant_code(email)
call("auth.verifyEmail.wrong", "POST", "/auth/verify-email", {"email": email, "code": "000000"})
plant_code(email)
_, login = call("auth.verifyEmail", "POST", "/auth/verify-email", {"email": email, "code": CODE},
                note="returns a token, so the client does not log in a second time")
token = login["accessToken"]
call("auth.login", "POST", "/auth/login", {"email": email, "password": PW})
call("auth.login.bad", "POST", "/auth/login", {"email": email, "password": "wrongpassword1"})
call("auth.checkEmail", "POST", "/auth/check-email", {"email": email})
call("auth.checkEmail.unknown", "POST", "/auth/check-email", {"email": "ghost@techies.vn"})
call("auth.resendOtp.unknown", "POST", "/auth/resend-otp",
     {"email": "ghost@techies.vn", "purpose": "REGISTRATION"},
     note="204 for an address with no account, so the endpoint cannot enumerate accounts")

# Password reset on its own account: it changes the password, and the one above is reused below.
reset_email = f"fe-reset-{uuid.uuid4().hex[:6]}@techies.vn"
register_verified(reset_email, "Le Thi Reset", "0903333333")
call("auth.resendOtp", "POST", "/auth/resend-otp",
     {"email": reset_email, "purpose": "PASSWORD_RESET"},
     note="step 1 of forgot-password; the response is 204 either way")
plant_code(reset_email)
call("auth.resetPassword", "POST", "/auth/reset-password",
     {"email": reset_email, "code": CODE, "newPassword": "newpassword9"})
call("auth.resetPassword.wrongCode", "POST", "/auth/reset-password",
     {"email": reset_email, "code": "000000", "newPassword": "newpassword9"})

# --- user -----------------------------------------------------------------
call("user.me", "GET", "/users/me", token=token)
call("user.update", "PUT", "/users/me", {"fullName": "Nguyen Van B", "phone": "0909999999"}, token)
call("user.unauthenticated", "GET", "/users/me")

# --- avatar ---------------------------------------------------------------
# A real 1x1 PNG, so the captured examples show genuine responses.
import base64, urllib.parse
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def upload_avatar(key, data, content_type, filename, note=None):
    boundary = "----techiesdocs"
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(API + "/users/me/avatar", method="POST", data=body)
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            status, payload = r.status, None
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        status, payload = e.code, (json.loads(raw) if raw.strip() else None)
    captured[key] = {"method": "POST", "path": "/users/me/avatar", "request": None,
                     "status": status, "response": payload, "note": note}


upload_avatar("avatar.upload", PNG, "image/png", "profile.png",
              note="multipart/form-data, field name 'file'")
call("avatar.userWithAvatar", "GET", "/users/me", token=token,
     note="avatarUrl is populated once an image exists")
upload_avatar("avatar.rejectDisguised", b"#!/bin/sh\nrm -rf /\n", "image/png", "evil.png",
              note="a non-image uploaded as image/png is refused on its bytes")
upload_avatar("avatar.rejectGif", b"GIF89a" + b"\x00" * 100, "image/gif", "me.gif")

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

# --- checkout: card orders wait for payment -------------------------------
_, placed = call("checkout.awaitingPayment", "POST", "/checkout",
                 {"addressId": address_id, "paymentMethod": "MOCK_CARD"}, token,
                 note="HTTP 200, order.status = AWAITING_PAYMENT -- stock held, now take the customer to pay")

# --- payment: reported as paid --------------------------------------------
call("payment.success", "POST", f"/orders/{placed['order']['id']}/payment",
     {"result": "SUCCESS", "transactionRef": "TXN-DEMO-0001"}, token,
     note="order.status = CONFIRMED, cart cleared")

# --- payment: reported as failed ------------------------------------------
call(None, "POST", "/cart/items", {"productId": product["id"], "quantity": 1}, token)
_, declined = call("checkout.declined", "POST", "/checkout",
                   {"addressId": address_id, "paymentMethod": "MOCK_CARD"}, token)
call("payment.failed", "POST", f"/orders/{declined['order']['id']}/payment",
     {"result": "FAILED", "failureReason": "Card declined by issuer"}, token,
     note="stock is returned, order.status = FAILED, the cart is kept for a retry")

# --- checkout: out of stock ----------------------------------------------
_, dead = call(None, "GET", "/products?keyword=MSI%20Modern&size=1")
if dead["content"]:
    call(None, "POST", "/cart/items", {"productId": dead["content"][0]["id"], "quantity": 1}, token)
    call("checkout.outOfStock", "POST", "/checkout",
         {"addressId": address_id, "paymentMethod": "COD"}, token,
         note="HTTP 200, order.status = FAILED, failureCode = OUT_OF_STOCK")

# --- coupons --------------------------------------------------------------
call(None, "POST", "/cart/items", {"productId": product["id"], "quantity": 1}, token)
_, couponed = call("checkout.coupon", "POST", "/checkout",
                   {"addressId": address_id, "paymentMethod": "COD", "couponCode": "FREESHIP30K"}, token,
                   note="discount comes off the subtotal; code and amount are snapshotted")
call(None, "POST", "/cart/items", {"productId": product["id"], "quantity": 1}, token)
call("checkout.coupon.rejected", "POST", "/checkout",
     {"addressId": address_id, "paymentMethod": "COD", "couponCode": "EXPIRED100K"}, token,
     note="409 before any order exists -- fix it on the checkout screen")

# --- reviews --------------------------------------------------------------
_order = (couponed or {}).get("order") or {}
if _order.get("id"):
    _, _detail = call(None, "GET", f"/orders/{_order['id']}", token=token)
    _lines = _detail.get("items") or []
    if _lines:
        call("reviews.write", "POST", f"/orders/{_order['id']}/reviews",
             {"reviews": [{"orderItemId": _lines[0]["id"], "rating": 5,
                           "comment": "Sản phẩm tốt, giao hàng nhanh."}]}, token,
             note="returns the order, so the app sees the updated reviewed flags")
        call("reviews.duplicate", "POST", f"/orders/{_order['id']}/reviews",
             {"reviews": [{"orderItemId": _lines[0]["id"], "rating": 1, "comment": "lần hai"}]}, token,
             note="409 ALREADY_REVIEWED -- one review per order line")
call("reviews.product", "GET", f"/products/{product['id']}/reviews?size=5",
     note="served by order-service via the gateway, not catalog")

# --- orders ---------------------------------------------------------------
_, orders = call("orders.list", "GET", "/orders", token=token)
confirmed = [o for o in orders["content"] if o["status"] == "CONFIRMED"]
if confirmed:
    oid = confirmed[0]["id"]
    call("orders.detail", "GET", f"/orders/{oid}", token=token)
    call("orders.status", "PUT", f"/orders/{oid}/status", {"status": "COMPLETED"}, token,
         note="demo shortcut -- nothing else moves an order on from CONFIRMED")
    call("orders.status.pending", "PUT", f"/orders/{oid}/status", {"status": "PENDING"}, token,
         note="400 VALIDATION_ERROR -- PENDING is an internal saga state")
    # Put it back before the cancel examples: a COMPLETED order is not cancellable.
    call(None, "PUT", f"/orders/{oid}/status", {"status": "CONFIRMED"}, token)
    call("orders.cancel", "POST", f"/orders/{oid}/cancel", token=token)
    call("orders.cancel.again", "POST", f"/orders/{oid}/cancel", token=token,
         note="cancelling twice is refused")

# --- AI review summary ----------------------------------------------------
# Needs a product with at least three reviews. The seeded ones from V7 are spread around the
# catalogue, so scan for one rather than pin an id that a reseed could move.
#
# Costs one Gemini request the first time only: the summary is cached on the review count, so
# regenerating these docs against an unchanged catalogue spends nothing.
_, _wide = call(None, "GET", "/products?size=40")
reviewed = unreviewed = None
for candidate in _wide["content"]:
    _, _revs = call(None, "GET", f"/products/{candidate['id']}/reviews?size=1")
    total = (_revs or {}).get("total", 0)
    if total >= 3 and reviewed is None:
        reviewed = candidate
    elif total == 0 and unreviewed is None:
        unreviewed = candidate
    if reviewed and unreviewed:
        break

if reviewed:
    call("reviews.summary", "GET", f"/products/{reviewed['id']}/review-summary",
         note="written by ai-service, cached here on the review count it was written from")
if unreviewed:
    call("reviews.summary.none", "GET", f"/products/{unreviewed['id']}/review-summary",
         note="200 with a null body -- under three reviews is a normal answer, not an error")

# --- loyalty: points, tiers and gifts -------------------------------------
# Its own account, so the examples read as a clean walk from tier 0 rather than inheriting
# whatever the order examples above left behind.
DEMO_A = "9c57e463-c2ec-560b-b0ca-c84f6a7a56e7"       # 10.000.000d -> tier 1
GIFT_PHONE_CASE = "ce2eb2ca-a432-5d29-9755-1f4f1abf2a14"   # 500 points, min tier 0
GIFT_EARBUDS = "5a895aa7-839d-5f07-9729-7f3a68806ac1"      # 12.000 points, min tier 3

loyal_email = f"loyal-demo-{uuid.uuid4().hex[:6]}@techies.vn"
ltoken = register_verified(loyal_email, "Tran Thi Loyal", "0902222222")
_, laddr = call(None, "POST", "/addresses", {
    "recipientName": "Tran Thi Loyal", "phone": "0902222222", "line1": "45 Nguyen Trai",
    "ward": "Ben Thanh", "district": "Quan 1", "province": "Ho Chi Minh", "isDefault": True}, ltoken)

call("loyalty.me.fresh", "GET", "/loyalty/me", token=ltoken,
     note="tier 0 with the ladder attached -- the app never hardcodes a threshold or names a tier")

call(None, "POST", "/cart/items", {"productId": DEMO_A, "quantity": 1}, ltoken)
_, lorder = call(None, "POST", "/checkout",
                 {"addressId": laddr["id"], "paymentMethod": "COD"}, ltoken)
call("loyalty.order.completed", "PUT", f"/orders/{lorder['order']['id']}/status",
     {"status": "COMPLETED"}, ltoken,
     note="the only thing that credits points -- nothing reaches COMPLETED on its own")

call("loyalty.me.tier1", "GET", "/loyalty/me", token=ltoken,
     note="10.000.000d of goods earned 10.000 points, which is tier 1")
call("loyalty.vouchers", "GET", "/loyalty/vouchers", token=ltoken,
     note="submit the code as couponCode at checkout, like a coupon")
call("loyalty.gifts", "GET", "/loyalty/gifts", token=ltoken,
     note="eligible already accounts for tier, balance, stock and a previous claim")

call("loyalty.claim", "POST", f"/loyalty/gifts/{GIFT_PHONE_CASE}/claim", token=ltoken,
     note="claiming is the whole transaction -- the code is collected in store, and nothing tracks that")
call("loyalty.claim.again", "POST", f"/loyalty/gifts/{GIFT_PHONE_CASE}/claim", token=ltoken,
     note="409 GIFT_ALREADY_CLAIMED -- one per customer per gift, enforced by a UNIQUE")
call("loyalty.claim.tierTooLow", "POST", f"/loyalty/gifts/{GIFT_EARBUDS}/claim", token=ltoken,
     note="409 TIER_TOO_LOW, nothing written")
call("loyalty.claimedGifts", "GET", "/loyalty/claimed-gifts", token=ltoken,
     note="the code stays readable here indefinitely")
call("loyalty.points.notRouted", "POST", "/loyalty/points",
     {"orderRef": "ORD-100001", "userId": str(uuid.uuid4()), "amountSpent": 99000000}, ltoken,
     note="404 -- internal only, so nobody can award themselves points")

out = os.environ.get("OUT", "captured.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(captured, f, indent=2, ensure_ascii=False)
print(f"captured {len(captured)} endpoint examples")
for k, v in captured.items():
    print(f"  {v['status']:>3}  {v['method']:<6} {v['path'][:52]:<52}  {k}")
