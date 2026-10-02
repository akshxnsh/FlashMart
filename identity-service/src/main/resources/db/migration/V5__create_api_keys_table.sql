CREATE TABLE api_keys (
    id BIGSERIAL PRIMARY KEY,
    brand_id BIGINT NOT NULL REFERENCES brands (id),
    key_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uk_api_keys_key_hash UNIQUE (key_hash),
    CONSTRAINT ck_api_keys_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    -- SHA-256 as 64 lowercase hex characters. Rejects three different mistakes
    -- that all fail silently otherwise: an uppercase hash (Postgres would treat
    -- it as a different string, so the WHERE key_hash = ? lookup finds nothing
    -- and UNIQUE never sees the duplicate), a raw unhashed key, and a bcrypt
    -- hash (which is 60 chars starting '$2a$' and, being salted, could never
    -- be looked up by equality at all).
    CONSTRAINT ck_api_keys_key_hash_shaped CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    -- status and revoked_at can never disagree.
    CONSTRAINT ck_api_keys_revoked_at_matches_status
        CHECK ((status = 'ACTIVE' AND revoked_at IS NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
);
