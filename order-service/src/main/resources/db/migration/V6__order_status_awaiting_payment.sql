-- Give "waiting for the customer to pay" a status of its own.
--
-- Payment moved out of the checkout request in V5: the order is placed, stock is held, and
-- the app settles the payment afterwards. That state was PENDING at first, but PENDING
-- already meant "created, nothing resolved yet" — the transient row the saga writes before
-- it touches inventory. Overloading it made an order the customer must act on
-- indistinguishable from one mid-saga, both in the API and when reading the table by hand.
--
-- So PENDING keeps its original meaning and never leaves the saga, and AWAITING_PAYMENT is
-- the state the app sees and acts on. COD never reaches it.
--
-- Postgres cannot alter a CHECK in place, so it is dropped and recreated.
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;

ALTER TABLE orders ADD CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'FAILED', 'CANCELLED'));

-- Orders placed between V5 and this migration are sitting in PENDING waiting to be paid for.
-- Anything still PENDING now is one of those: the saga's own PENDING rows only exist inside a
-- single request and never survive it.
UPDATE orders SET status = 'AWAITING_PAYMENT'
 WHERE status = 'PENDING' AND payment_method <> 'COD';
