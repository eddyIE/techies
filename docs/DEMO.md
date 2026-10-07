# Demo Walkthrough

Everything a marker needs to see, in order. Every command is copy-pasteable.

## Start the stack

```bash
cd techies
cp .env.example .env          # first time only
docker compose up --build -d

# wait for all six services to report healthy
docker compose ps
```

Only **port 8080** is published. Every other service is reachable solely on the compose
network — that is what makes the gateway's identity injection trustworthy.

One-command version of everything below:

```bash
python3 scripts/demo.py
```

## Fixtures

From `docs/SEED-IDS.md`. Chosen so each branch is reproducible:

| Purpose | Product | Seeded stock |
|---|---|---|
| Normal purchase | iPhone 15 Pro Max 256GB | 120 |
| **Oversell race** | Xiaomi 14 Ultra · Acer Nitro V 15 · Marshall Major V · Keychron K2 Pro | **1** |
| **OUT_OF_STOCK branch** | MSI Modern 14 C13M · Lenovo Tab P12 | **0** |
| 404-on-detail | Nothing Phone (2a) · Amazfit GTR 4 | inactive |

## 1. Register and log in

```bash
API=http://localhost:8080/api

curl -s -X POST $API/auth/register -H 'Content-Type: application/json' -d '{
  "email":"demo@techies.vn","password":"password1",
  "fullName":"Nguyen Van Demo","phone":"0901234567"}'

TOKEN=$(curl -s -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"demo@techies.vn","password":"password1"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
echo $TOKEN
```

The token lasts 30 days. There is no refresh endpoint and no logout — the client simply
discards it.

## 2. Browse (no token needed)

```bash
curl -s "$API/categories"
curl -s "$API/products?keyword=iphone&size=5"
curl -s "$API/products?keyword=bao%20hanh"     # accent-insensitive: matches "bảo hành"
curl -s "$API/products?sort=PRICE_ASC&size=5"
```

## 3. Address and cart

```bash
ADDR=$(curl -s -X POST $API/addresses -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "recipientName":"Nguyen Van Demo","phone":"0901234567","line1":"12 Nguyen Hue",
    "ward":"Ben Nghe","district":"Quan 1","province":"Ho Chi Minh","isDefault":true}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

PRODUCT=$(curl -s "$API/products?keyword=iphone%2015%20Pro" | python3 -c 'import sys,json;print(json.load(sys.stdin)["content"][0]["id"])')

curl -s -X POST $API/cart/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"productId\":\"$PRODUCT\",\"quantity\":2}"

curl -s "$API/stock/$PRODUCT"        # note the number before checking out
```

## 4. Successful checkout

```bash
curl -s -X POST $API/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"addressId\":\"$ADDR\",\"paymentMethod\":\"MOCK_CARD\",\"simulatePayment\":\"SUCCESS\"}"

curl -s "$API/stock/$PRODUCT"        # down by 2
curl -s $API/cart -H "Authorization: Bearer $TOKEN"   # empty
```

Expect `status: CONFIRMED`, stock reduced by 2, cart cleared.

## 5. The compensation demo — the one that matters

```bash
curl -s -X POST $API/cart/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"productId\":\"$PRODUCT\",\"quantity\":3}"

curl -s "$API/stock/$PRODUCT"        # record this number

curl -s -X POST $API/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"addressId\":\"$ADDR\",\"paymentMethod\":\"MOCK_CARD\",\"simulatePayment\":\"DECLINED\"}"

curl -s "$API/stock/$PRODUCT"        # IDENTICAL to the number above
curl -s $API/cart -H "Authorization: Bearer $TOKEN"   # cart still has the items
```

Three things to point out:

1. **HTTP 200 with `status: FAILED`**, not a 4xx. The app needs the order id to render the
   Payment Result screen and loop back to Checkout.
2. **Stock is exactly what it was before.** Step 5 deducted it; the compensating restore in
   step 6 put it back.
3. **The cart survives.** Clearing it would make the Order Flow retry loop impossible.

### Show the saga trail

```bash
docker compose exec -T postgres psql -U techies -d techies -c \
  "SELECT s.step_name, s.status, s.detail
     FROM orders.saga_steps s
     JOIN orders.orders o ON o.id = s.order_id
    WHERE o.status = 'FAILED'
    ORDER BY s.created_at;"
```

Look for `5-DEDUCT_STOCK | COMPENSATED`. That row is the distributed transaction rolling back.

And the matching inventory movements:

```bash
docker compose exec -T postgres psql -U techies -d techies -c \
  "SELECT order_ref, type, created_at FROM inventory.stock_movements ORDER BY created_at DESC LIMIT 6;"
```

One `DEDUCT` and one `RESTORE` for the same `order_ref`.

## 6. Out of stock — no payment is ever attempted

```bash
DEAD=$(curl -s "$API/products?keyword=MSI%20Modern" | python3 -c 'import sys,json;print(json.load(sys.stdin)["content"][0]["id"])')
curl -s "$API/stock/$DEAD"           # 0

curl -s -X POST $API/cart/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"productId\":\"$DEAD\",\"quantity\":1}"
curl -s -X POST $API/checkout -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"addressId\":\"$ADDR\",\"paymentMethod\":\"COD\"}"
```

`failureCode: OUT_OF_STOCK`, and the saga trail contains **no** `6-CHARGE_PAYMENT` row —
the saga stopped before payment because there was nothing to compensate.

## 7. Orders and cancellation

```bash
curl -s "$API/orders" -H "Authorization: Bearer $TOKEN"
ORDER=$(curl -s "$API/orders?status=CONFIRMED" -H "Authorization: Bearer $TOKEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["content"][0]["id"])')

curl -s -X POST "$API/orders/$ORDER/cancel" -H "Authorization: Bearer $TOKEN"
curl -s "$API/stock/$PRODUCT"        # stock returned

curl -s -X POST "$API/orders/$ORDER/cancel" -H "Authorization: Bearer $TOKEN"   # 409
```

## 8. The oversell race

Use one of the 1-unit products. Ten simultaneous checkouts, one winner:

```bash
./mvnw -pl inventory-service test -Dtest=StockServiceTest#concurrentDeductsCannotOversell
```

Ten threads contend for the last unit; exactly one succeeds, nine get 409, and stock lands
at 0 — never negative. Three overlapping guards make that true: the atomic
`UPDATE ... WHERE available >= qty`, `@Version` optimistic locking, and a
`CHECK (available >= 0)` constraint.

## 9. Loyalty: tier 0 to tier 3 in three orders

Three demo products priced for exactly this, in their own `demo` category so the real
catalogue is untouched. Ids are in `docs/SEED-IDS.md`.

Use a **fresh account** — the walk is about watching the tier climb from nothing.

```bash
DEMO_A=9c57e463-c2ec-560b-b0ca-c84f6a7a56e7   # 10.000.000d -> tier 1
DEMO_B=0c00e340-1aee-5a44-90a5-1b734973daad   # 20.000.000d -> tier 2
DEMO_C=a55d0844-ed95-5ca5-b69b-374bcedf9e0e   # 30.000.000d -> tier 3

curl -s "$API/loyalty/me" -H "Authorization: Bearer $TOKEN"
# tier 0, lifetimePoints 0, pointsToNextTier 10000, and the whole ladder attached
```

Each order has to be pushed to `COMPLETED` by hand. Nothing else sets it — that is the same
missing-actor gap `COMPLETED` has always had, not a new one.

```bash
buy_and_complete() {
  curl -s -X POST $API/cart/items -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "{\"productId\":\"$1\",\"quantity\":1}" > /dev/null
  OID=$(curl -s -X POST $API/checkout -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"addressId\":\"$ADDR\",\"paymentMethod\":\"COD\"}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["order"]["id"])')
  curl -s -X PUT "$API/orders/$OID/status" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"status":"COMPLETED"}' > /dev/null
  curl -s "$API/loyalty/me" -H "Authorization: Bearer $TOKEN" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print("tier",d["tier"],"| lifetime",d["lifetimePoints"],"| balance",d["balance"],"| toNext",d["pointsToNextTier"])'
}

buy_and_complete $DEMO_A    # tier 1 | lifetime 10000 | balance 10000 | toNext 20000
buy_and_complete $DEMO_B    # tier 2 | lifetime 30000 | balance 30000 | toNext 30000
buy_and_complete $DEMO_C    # tier 3 | lifetime 60000 | balance 60000 | toNext None
```

One voucher per tier crossed, issued in the same transaction as the points:

```bash
curl -s "$API/loyalty/vouchers" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;[print(v["code"], str(v["discountPercent"])+"%") for v in json.load(sys.stdin)]'
# TIER1-XXXXXXXX 10%
# TIER2-XXXXXXXX 30%
# TIER3-XXXXXXXX 50%
```

Spend one at checkout as `couponCode`, exactly like a coupon. Coupons are resolved locally
first and an unknown code falls through to loyalty, so the app never has to tell them apart:

```bash
VOUCHER=$(curl -s "$API/loyalty/vouchers" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["code"])')

curl -s -X POST $API/cart/items -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"productId\":\"$DEMO_A\",\"quantity\":1}" > /dev/null
curl -s -X POST $API/checkout -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"addressId\":\"$ADDR\",\"paymentMethod\":\"COD\",\"couponCode\":\"$VOUCHER\"}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin)["order"];print("subtotal",d["subtotal"],"discount",d["discount"],"total",d["total"])'
# discount is 10% of the subtotal, snapshotted onto the order like a coupon's
```

Cancel that order and the voucher comes back unconsumed. This is the third release path, and
the one that is easy to miss: nothing went wrong, the customer simply changed their mind, so
destroying a reward they earned would be the wrong answer.

```bash
VOUCHER_ORDER=$(curl -s "$API/orders?status=CONFIRMED" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["content"][0]["id"])')
curl -s -X POST "$API/orders/$VOUCHER_ORDER/cancel" -H "Authorization: Bearer $TOKEN" > /dev/null
curl -s "$API/loyalty/vouchers" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;[print(v["code"], "consumedAt:", v["consumedAt"]) for v in json.load(sys.stdin)]'
# consumedAt back to None -- spendable again
```

## 10. Claiming a gift

```bash
curl -s "$API/loyalty/gifts" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;[print(g["pointsCost"], "t"+str(g["minTier"]), "stock="+str(g["inStock"]), "eligible="+str(g["eligible"]), g["name"]) for g in json.load(sys.stdin)]'
```

`eligible` is the whole claim rule answered in advance, so the app greys a card out without
re-implementing it. The seeded catalogue reaches every refusal on purpose: a tier 3 gift, one
with no stock, and one priced past any reachable balance.

```bash
GIFT=ce2eb2ca-a432-5d29-9755-1f4f1abf2a14        # Op lung silicon, 500 points, tier 0
curl -s -X POST "$API/loyalty/gifts/$GIFT/claim" -H "Authorization: Bearer $TOKEN"
# {"redemptionId":"...","code":"GIFT-XXXX-XXXX","giftName":"Op lung silicon","pointsSpent":500,...}
```

Claiming is the whole transaction. The code is read at a counter and **nothing in the app
tracks that** — there is no status to poll. Claim again and it is refused, by a UNIQUE rather
than a read-then-write, so a double tap cannot slip through:

```bash
curl -s -X POST "$API/loyalty/gifts/$GIFT/claim" -H "Authorization: Bearer $TOKEN"
# 409 GIFT_ALREADY_CLAIMED
```

The other three refusals. Two work from any tier:

```bash
curl -s -X POST "$API/loyalty/gifts/e20b380a-f62c-56eb-9ba4-f7cbf404ee5c/claim" -H "Authorization: Bearer $TOKEN"  # 409 GIFT_OUT_OF_STOCK
curl -s -X POST "$API/loyalty/gifts/378bdc0f-8cea-57b3-a646-86b07ac39296/claim" -H "Authorization: Bearer $TOKEN"  # 409 INSUFFICIENT_POINTS
```

`TIER_TOO_LOW` needs an account that has **not** reached tier 3 yet — at tier 3 nothing in the
seeded catalogue is out of reach, which is rather the point of climbing. Run it between the
first and the second order above, or from a second fresh account:

```bash
curl -s -X POST "$API/loyalty/gifts/5a895aa7-839d-5f07-9729-7f3a68806ac1/claim" -H "Authorization: Bearer $TOKEN"  # 409 TIER_TOO_LOW
```

The code stays readable indefinitely, and claiming never touched the lifetime total, so the
tier is unchanged:

```bash
curl -s "$API/loyalty/claimed-gifts" -H "Authorization: Bearer $TOKEN"
curl -s "$API/loyalty/me" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("tier",d["tier"],"lifetime",d["lifetimePoints"],"balance",d["balance"])'
# balance fell by 500, lifetime and tier did not move
```

## 11. The AI review summary

```bash
# Any product with at least three reviews. V7 seeds several.
PID=$(curl -s "$API/products?size=40" | python3 -c '
import sys,json,urllib.request
for p in json.load(sys.stdin)["content"]:
    with urllib.request.urlopen("http://localhost:8080/api/products/%s/reviews?size=1" % p["id"]) as r:
        if json.load(r)["total"] >= 3:
            print(p["id"]); break')

time curl -s "$API/products/$PID/review-summary"     # ~10s if the count changed since last time
time curl -s "$API/products/$PID/review-summary"     # cached, milliseconds
```

Caching is keyed on the review count, not a clock, so a product nobody has reviewed since
costs nothing however often its page is opened. Write a new review and the next read
regenerates.

A product with fewer than three reviews answers **200 with an empty body**, which is a normal
answer and not an error:

```bash
curl -s -o /dev/null -w 'status=%{http_code} bytes=%{size_download}\n' \
  "$API/products/$DEMO_A/review-summary"      # status=200 bytes=0
```

## 12. Security checks

```bash
curl -s -o /dev/null -w '%{http_code}\n' $API/cart                      # 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/stock/deduct      # 404, not routed
curl -s -o /dev/null -w '%{http_code}\n' $API/internal/addresses/x      # 404, not routed

# Loyalty's internal half is not routed either: a customer must never award themselves points,
# and releasing their own voucher mid-checkout would hand them the discount twice.
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/loyalty/points -H "Authorization: Bearer $TOKEN"                   # 404
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/loyalty/vouchers/X/consume -H "Authorization: Bearer $TOKEN"       # 404
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/loyalty/vouchers/X/release -H "Authorization: Bearer $TOKEN"       # 404

# And neither is the review summary generator, which would otherwise let any logged-in user
# spend the Gemini quota by reloading a product page.
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/ai/review-summary -H "Authorization: Bearer $TOKEN"                # 404

# Forged identity header without a token gets nowhere:
curl -s -o /dev/null -w '%{http_code}\n' $API/cart -H 'X-User-Id: 00000000-0000-0000-0000-000000000001'   # 401
```

## API documentation

Each service serves its own Swagger UI inside the network:

```bash
docker compose exec -T catalog-service wget -qO- http://localhost:8082/v3/api-docs | head -c 200
```

To open one in a browser, publish its port temporarily:

```bash
docker compose run --rm --service-ports catalog-service
# then http://localhost:8082/swagger-ui.html
```
