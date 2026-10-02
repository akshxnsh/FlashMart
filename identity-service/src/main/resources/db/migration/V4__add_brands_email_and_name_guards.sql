-- Backfills the guards added with `users`. `ck_brands_email_normalized` only
-- asks "is this already lowercase and trimmed?", which an empty string
-- satisfies, so '' was insertable as an email; nothing checked `name` at all,
-- and nothing required an '@'. Verified against this schema before writing.
ALTER TABLE brands
    ADD CONSTRAINT ck_brands_email_present
        CHECK (length(email) > 0);

ALTER TABLE brands
    ADD CONSTRAINT ck_brands_email_shaped
        CHECK (position('@' in email) > 1 AND position('@' in email) < length(email));

ALTER TABLE brands
    ADD CONSTRAINT ck_brands_name_present
        CHECK (length(btrim(name)) > 0);
