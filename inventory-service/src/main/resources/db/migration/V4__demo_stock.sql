-- Stock for the demo products added by catalog V6. Deep enough that walking the ladder
-- repeatedly, in a demo or a test run, never hits OUT_OF_STOCK and derails the point.
INSERT INTO stock_items (product_id, available, version, updated_at) VALUES
  ('9c57e463-c2ec-560b-b0ca-c84f6a7a56e7', 999, 0, NOW()),
  ('0c00e340-1aee-5a44-90a5-1b734973daad', 999, 0, NOW()),
  ('a55d0844-ed95-5ca5-b69b-374bcedf9e0e', 999, 0, NOW());
