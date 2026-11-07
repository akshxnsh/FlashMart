-- Buyer refresh tokens (decisions.md, "Day 8: auth, gateway identity, rate
-- limiting, browse", points 3 and 4).
--
-- The token itself is a random value handed to the buyer once and NEVER
-- stored. Only its SHA-256 is kept, so this table is not a list of working
-- credentials: someone who reads it cannot log in as anyone.
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    -- Same-service FK: a token always belongs to an existing buyer.
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    -- Stamped by the DATABASE clock at insert (clock_timestamp() + ttl), and
    -- compared against the database clock in the rotation CAS, so one clock
    -- both writes and reads it (decisions.md, "Who stamps a time column").
    expires_at TIMESTAMPTZ NOT NULL,
    -- NULL = live. Set once, only by the CAS writes (rotate, logout,
    -- revoke-all), never by an entity save.
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    -- Lookup is WHERE token_hash = ?; also makes a duplicate hash impossible.
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    -- 64 lowercase hex characters, as on api_keys: an uppercase hash or a raw
    -- token stored by mistake would never be found by the equality lookup.
    CONSTRAINT ck_refresh_tokens_token_hash_shaped CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_tokens_expires_after_created CHECK (expires_at > created_at)
);

-- The one query that reads by user: reuse detection revokes every LIVE token
-- of a user (UPDATE ... WHERE user_id = ? AND revoked_at IS NULL). Revoked rows
-- pile up with every rotation and that query never wants them, so the index
-- holds live rows only.
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
