CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_users_email_present CHECK (length(email) > 0),
    CONSTRAINT ck_users_email_shaped CHECK (position('@' in email) > 1 AND position('@' in email) < length(email)),
    CONSTRAINT ck_users_name_present CHECK (length(btrim(name)) > 0)
);

CREATE TRIGGER trg_users_bump_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION bump_updated_at();
