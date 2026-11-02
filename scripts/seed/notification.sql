-- Seed for the Notification database (notification-service). Written, not run.
--
-- The contact caches are normally filled by BrandUpserted / UserUpserted
-- events. Identity's seed writes no events, so they are filled by hand here,
-- keyed by Identity's seeded ids (brand 1, users 1 to 3). Emails must match
-- identity.sql.
--
-- updated_at is FIXED, not now(): the cache applies an event only if its
-- updated_at is strictly newer, so a real event from Identity (stamped now)
-- will always win over this seed, and re-running this script is repeatable.
-- The upsert below overwrites unconditionally, which is right for a seed and
-- would be wrong for the real consumer.

BEGIN;

INSERT INTO brand_contacts (brand_id, email, updated_at) VALUES
    (1, 'sales@zephyr-athletics.test', '2026-01-01T00:00:00Z')
ON CONFLICT (brand_id) DO UPDATE
    SET email = EXCLUDED.email,
        updated_at = EXCLUDED.updated_at;

INSERT INTO buyer_contacts (user_id, email, updated_at) VALUES
    (1, 'asha.verma@buyers.test',  '2026-01-01T00:00:00Z'),
    (2, 'rohan.mehta@buyers.test', '2026-01-01T00:00:00Z'),
    (3, 'neha.kapoor@buyers.test', '2026-01-01T00:00:00Z')
ON CONFLICT (user_id) DO UPDATE
    SET email = EXCLUDED.email,
        updated_at = EXCLUDED.updated_at;

COMMIT;
