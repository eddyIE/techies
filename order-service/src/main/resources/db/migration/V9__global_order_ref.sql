-- Order references were numbered per day (ORD-yyyyMMdd-NNNN), so the counter restarted every
-- morning and a customer's order list never read as an ascending list of numbers. One global
-- sequence gives ORD-100001, ORD-100002, ... instead.
--
-- Only the generator changes. Orders already placed keep the references they were given:
-- inventory.stock_movements links to an order by this string, so rewriting history there
-- would break the idempotency key that stops stock being restored twice.
CREATE SEQUENCE order_ref_seq START WITH 100001 INCREMENT BY 1;

DROP TABLE order_ref_counters;
