-- Record the payment provider's reference on the order.
--
-- Payment used to be settled inside the checkout request, so there was nothing to remember:
-- the order came back already CONFIRMED or FAILED. The app now pays after the order exists
-- and reports the outcome, which arrives with the provider's transaction id. Keeping it is
-- what makes a payment traceable back to a real transaction when a customer disputes one.
--
-- Nullable: COD orders never have one, and orders placed before this column existed keep NULL.
ALTER TABLE orders ADD COLUMN payment_ref VARCHAR(64);
