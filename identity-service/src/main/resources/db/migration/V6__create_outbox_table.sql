CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    -- A brand id OR a user id, told apart by event_type. No FK is possible:
    -- brand 7 and user 7 are different rows sharing a number, so any query by
    -- aggregate_id alone is wrong without event_type beside it.
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- NULL means "not yet published". A boolean would answer whether a row was
    -- sent but never when, leaving relay lag invisible and giving the cleanup
    -- job nothing to delete by.
    published_at TIMESTAMPTZ,
    -- A row that can never be published (an oversized payload, say) would
    -- otherwise be retried forever and silently, holding up everything behind
    -- it. Same shape as refunds and notification_log.
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_event_type_present CHECK (length(btrim(event_type)) > 0)
);

-- The relay reads only unpublished rows, so published ones are never indexed.
-- The index therefore stays small however many rows accumulate, unlike an
-- index over (published, created_at) covering the whole table.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
