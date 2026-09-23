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

## 9. Security checks

```bash
curl -s -o /dev/null -w '%{http_code}\n' $API/cart                      # 401
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/stock/deduct      # 404, not routed
curl -s -o /dev/null -w '%{http_code}\n' $API/internal/addresses/x      # 404, not routed

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
