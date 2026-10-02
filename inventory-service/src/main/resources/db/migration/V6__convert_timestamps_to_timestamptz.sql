-- A zone-less TIMESTAMP compared against now() is interpreted using the
-- connection's TimeZone setting, so the same sale at the same instant read as
-- open from an Asia/Kolkata connection and not open from a UTC one. TIMESTAMPTZ
-- stores one absolute moment, fixed at write time, so every connection agrees.
--
-- USING names the zone the old values meant instead of inheriting the
-- connection's, which is what makes this conversion identical on every machine.
-- These tables held no rows when this ran, so the zone chosen cannot shift any
-- data; UTC is used so the file does not imply a zone it never needed.

ALTER TABLE flash_sales
    ALTER COLUMN start_time TYPE TIMESTAMPTZ USING start_time AT TIME ZONE 'UTC',
    ALTER COLUMN end_time   TYPE TIMESTAMPTZ USING end_time   AT TIME ZONE 'UTC';

ALTER TABLE stock_reservations
    ALTER COLUMN reserved_at TYPE TIMESTAMPTZ USING reserved_at AT TIME ZONE 'UTC';

ALTER TABLE products
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';
