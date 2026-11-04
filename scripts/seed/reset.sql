-- Reset between load-test runs. Run and verified on 2026-11-04 (see README.md).
--
-- Run through seed.sh (or psql with -v inventory_db=... -v order_db=...).
-- It touches two databases, which is why it uses \connect: the services never
-- share one. It needs PGPASSWORD set, because \connect does not carry the
-- password over by itself.
--
-- It does not touch Identity or Notification: nothing in a load test writes to
-- them, and their seed is upserted by id.

\connect :"inventory_db"

BEGIN;

-- Reservations first: they point at flash_sale_items.
DELETE FROM stock_reservations;

-- Restore the three windows exactly as inventory.sql sets them (re-anchored
-- to now(), so LIVE is live again).
UPDATE flash_sales SET start_time = now() - interval '1 minute', end_time = now() + interval '24 hours' WHERE id = 1;
UPDATE flash_sales SET start_time = now() + interval '1 hour',   end_time = now() + interval '2 hours' WHERE id = 2;
UPDATE flash_sales SET start_time = now() - interval '2 hours',  end_time = now() - interval '1 hour'  WHERE id = 3;

-- Stock back to full. version goes back to 0 so every run starts the same.
UPDATE flash_sale_items SET available_stock = total_stock, version = 0;

COMMIT;

\connect :"order_db"

BEGIN;

-- Children before parents: refunds and order_items both reference orders.
-- outbox has no foreign key but belongs to the same orders.
DELETE FROM refunds;
DELETE FROM order_items;
DELETE FROM outbox;
DELETE FROM orders;

COMMIT;
