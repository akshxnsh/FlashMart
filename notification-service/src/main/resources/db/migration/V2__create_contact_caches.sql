-- Notification keeps its own copy of every contact address so it has no
-- runtime dependency on Identity: Identity being down must not stop receipts.
--
-- The primary key is the OTHER service's id, deliberately breaking the
-- BIGSERIAL convention. There is exactly one cache row per brand, so the
-- brand's own id is the natural key; a surrogate id would permit two rows for
-- one brand unless a UNIQUE were also added, which is strictly worse.
--
-- Two tables rather than one with a type column: brand 7 and user 7 are
-- different things, so a single table would need (type, id) as its key and
-- would reintroduce exactly the ambiguity outbox.aggregate_id has.
--
-- updated_at is IDENTITY'S timestamp, carried on the event, not the time this
-- row was written. The upsert is conditional on it:
--   ON CONFLICT (brand_id) DO UPDATE ... WHERE brand_contacts.updated_at < EXCLUDED.updated_at
-- so a stale event silently loses and delivery order never has to be
-- guaranteed. The strict `<` is correct ONLY because Identity's
-- bump_updated_at trigger makes updated_at strictly increasing per row: two
-- real changes can never share a timestamp, so equal always means redelivery.
--
-- The email guards repeat brands/users. Notification is a separate database
-- and cannot assume an event honoured the publisher's rules; without them a
-- malformed address sits in the cache until a send fails.

CREATE TABLE brand_contacts (
    brand_id BIGINT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_brand_contacts_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_brand_contacts_email_present CHECK (length(email) > 0),
    CONSTRAINT ck_brand_contacts_email_shaped
        CHECK (position('@' in email) > 1 AND position('@' in email) < length(email))
);

CREATE TABLE buyer_contacts (
    user_id BIGINT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_buyer_contacts_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_buyer_contacts_email_present CHECK (length(email) > 0),
    CONSTRAINT ck_buyer_contacts_email_shaped
        CHECK (position('@' in email) > 1 AND position('@' in email) < length(email))
);
