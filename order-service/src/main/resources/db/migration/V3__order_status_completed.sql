-- Allow COMPLETED as an order status.
--
-- Nothing in the API sets it: with no management app there is no actor to move an order from
-- CONFIRMED to COMPLETED. It exists so the lifecycle can be shown end to end in a demo, and
-- so a row written by hand (or by a future admin tool) is accepted rather than rejected by
-- the constraint.
--
-- Postgres cannot alter a CHECK in place, so it is dropped and recreated.
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;

ALTER TABLE orders ADD CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'FAILED', 'CANCELLED'));
