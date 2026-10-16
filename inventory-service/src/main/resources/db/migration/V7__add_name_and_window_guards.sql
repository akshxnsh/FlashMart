-- Backfills guards that Inventory's V1-V6 predate. Identity's V4 did the same
-- job for `brands` once `users` revealed the hole; Inventory never got its own
-- pass, so `flash_sales` carried a single CHECK (on status) and neither
-- `flash_sales.name` nor `products.name` was checked at all, while
-- `brands.name` and `users.name` both are. Verified against this schema before
-- writing: no other Inventory column is missing a guard it should have.

-- Liveness is half-open: status != 'CANCELLED' AND start_time <= now < end_time.
-- An end_time at or before start_time makes that interval EMPTY, so the sale can
-- never be live -- and the row then has no way out, because terms freeze once
-- now >= start_time and a started sale cannot be cancelled. The result is a row
-- that can never open, never be edited and never be cancelled.
--
-- This is deliberately stricter than the mispriced-sale case the design accepts
-- as unfixable: a mispriced sale at least RUNS. A backwards window produces a
-- dead row nobody chose. Unlike "frozen once the sale opens" -- which needs to
-- know `now` relative to the row's own start_time and so stays a service-layer
-- rule -- this one is a pure within-row comparison, which is exactly what a
-- CHECK can enforce.
--
-- Strict `>`: end_time = start_time gives the same empty half-open interval.
ALTER TABLE flash_sales
    ADD CONSTRAINT ck_flash_sales_window_ordered
        CHECK (end_time > start_time);

-- btrim, not length(name) > 0: NOT NULL already rejects NULL and a plain length
-- check still admits '   ', which is blank to every reader of it. Same shape as
-- ck_brands_name_present.
ALTER TABLE flash_sales
    ADD CONSTRAINT ck_flash_sales_name_present
        CHECK (length(btrim(name)) > 0);

-- products.name is snapshotted into order_items.product_name at reservation time
-- and printed on the buyer's receipt, so a blank name survives into a permanent
-- record of a completed purchase. UNIQUE(brand_id, name) would also happily
-- admit exactly one blank-named product per brand.
ALTER TABLE products
    ADD CONSTRAINT ck_products_name_present
        CHECK (length(btrim(name)) > 0);
