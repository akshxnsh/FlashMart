-- Seed for the Inventory database (inventory-service). Written, not run.
--
-- brand_id 1 is Identity's seeded brand (identity.sql). It is a plain id with
-- no foreign key, by design: services never share a database.
--
-- Three sales, windows given as now() +/- interval so they are right whenever
-- this runs:
--   1 LIVE      started 1 minute ago, ends in 1 hour
--   2 UPCOMING  starts in 1 hour, ends in 2 hours
--   3 ENDED     started 2 hours ago, ended 1 hour ago
-- "Live" is computed from the window, never stored: all three have status
-- 'ACTIVE'. The windows are fixed at seed time, so they drift: re-run
-- seed.sh (or reset.sql) before a test that needs the LIVE sale to be live.
--
-- No reservations are seeded. Safe to run twice: every row is an upsert on its
-- id, and a re-run also restores windows and stock.

BEGIN;

INSERT INTO products (id, brand_id, name) VALUES
    (1, 1, 'Zephyr Runner Shoe'),
    (2, 1, 'Zephyr Court Tee'),
    (3, 1, 'Zephyr Trail Cap')
ON CONFLICT (id) DO UPDATE
    SET brand_id = EXCLUDED.brand_id,
        name = EXCLUDED.name;

INSERT INTO flash_sales (id, brand_id, name, start_time, end_time, status) VALUES
    (1, 1, 'Zephyr Live Drop',     now() - interval '1 minute', now() + interval '1 hour',  'ACTIVE'),
    (2, 1, 'Zephyr Upcoming Drop', now() + interval '1 hour',   now() + interval '2 hours', 'ACTIVE'),
    (3, 1, 'Zephyr Ended Drop',    now() - interval '2 hours',  now() - interval '1 hour',  'ACTIVE')
ON CONFLICT (id) DO UPDATE
    SET brand_id = EXCLUDED.brand_id,
        name = EXCLUDED.name,
        start_time = EXCLUDED.start_time,
        end_time = EXCLUDED.end_time,
        status = EXCLUDED.status;

-- total_stock = available_stock (no sales yet). The LIVE sale has the two
-- stock sizes the load test needs: a big pool and a scarce one.
INSERT INTO flash_sale_items (id, flash_sale_id, product_id, sale_price, total_stock, available_stock) VALUES
    (1, 1, 1, 4999.00, 500, 500),
    (2, 1, 2,  799.00,  50,  50),
    (3, 2, 3,  599.00, 100, 100),
    (4, 3, 1, 4499.00,  20,  20)
ON CONFLICT (id) DO UPDATE
    SET flash_sale_id = EXCLUDED.flash_sale_id,
        product_id = EXCLUDED.product_id,
        sale_price = EXCLUDED.sale_price,
        total_stock = EXCLUDED.total_stock,
        available_stock = EXCLUDED.available_stock,
        version = 0;

-- Explicit ids do not advance a BIGSERIAL (see identity.sql).
SELECT setval(pg_get_serial_sequence('products', 'id'), (SELECT max(id) FROM products));
SELECT setval(pg_get_serial_sequence('flash_sales', 'id'), (SELECT max(id) FROM flash_sales));
SELECT setval(pg_get_serial_sequence('flash_sale_items', 'id'), (SELECT max(id) FROM flash_sale_items));

COMMIT;
