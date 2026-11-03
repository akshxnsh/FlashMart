-- Seed for the Identity database (identity-service). Written, not run.
--
-- 1 brand and 3 buyers with FIXED ids, so the other seed files can refer to
-- them. Safe to run twice: each row is an upsert on its id.
--
-- password_hash is a placeholder that can never log in: it is not a valid
-- hash in any format, so no password can match it. Real hashes arrive on
-- Day 8 (security). Do not copy this value anywhere else.
--
-- Emails are already lowercase and trimmed (ck_*_email_normalized).
-- Note: this inserts rows directly, so it writes NO outbox rows and no
-- BrandUpserted / UserUpserted event is published. notification.sql seeds the
-- contact caches by hand for that reason.

BEGIN;

INSERT INTO brands (id, name, email, password_hash) VALUES
    (1, 'Zephyr Athletics', 'sales@zephyr-athletics.test', 'SEED-PLACEHOLDER-NOT-A-REAL-HASH')
ON CONFLICT (id) DO UPDATE
    SET name = EXCLUDED.name,
        email = EXCLUDED.email,
        password_hash = EXCLUDED.password_hash;

INSERT INTO users (id, name, email, password_hash) VALUES
    (1, 'Asha Verma',  'asha.verma@buyers.test',  'SEED-PLACEHOLDER-NOT-A-REAL-HASH'),
    (2, 'Rohan Mehta', 'rohan.mehta@buyers.test', 'SEED-PLACEHOLDER-NOT-A-REAL-HASH'),
    (3, 'Neha Kapoor', 'neha.kapoor@buyers.test', 'SEED-PLACEHOLDER-NOT-A-REAL-HASH')
ON CONFLICT (id) DO UPDATE
    SET name = EXCLUDED.name,
        email = EXCLUDED.email,
        password_hash = EXCLUDED.password_hash;

-- Explicit ids do not advance a BIGSERIAL. Without this, the first real
-- insert would try id 1 and fail on the primary key.
SELECT setval(pg_get_serial_sequence('brands', 'id'), (SELECT max(id) FROM brands));
SELECT setval(pg_get_serial_sequence('users', 'id'), (SELECT max(id) FROM users));

COMMIT;
