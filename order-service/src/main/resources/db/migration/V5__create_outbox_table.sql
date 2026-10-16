-- Identity's V6 shape, with its V7 event_type constraint folded in rather than
-- left as a follow-up: Order's guards all ship before the table holds rows.
-- Order's vocabulary differs, but nothing else about the shape does, and the
-- two must not drift -- the relay logic is the same in both services.
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    -- Always an order id here, unlike Identity's, where it is a brand id OR a
    -- user id told apart by event_type. Still no FK: the relay must be able to
    -- publish an event about a row that a later cleanup has deleted.
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- NULL means "not yet published". A boolean would answer whether a row was
    -- sent but never when, leaving relay lag invisible and giving the cleanup
    -- job nothing to delete by. Written by the database clock in the relay's
    -- own UPDATE.
    published_at TIMESTAMPTZ,
    -- A row that can never be published (an oversized payload, say) would
    -- otherwise be retried forever and silently, holding up everything behind
    -- it. Same shape as refunds and notification_log.
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_event_type_present CHECK (length(btrim(event_type)) > 0),
    -- Order publishes exactly two of the system's four events. Pinned for the
    -- reason Identity's V7 gives: a typo produces an event no consumer
    -- recognises, silently, and adding an event type is a documented decision
    -- rather than a free-text change.
    --
    -- Identity's two ('BrandUpserted', 'UserUpserted') must fail here. The
    -- vocabularies are per-service on purpose: Order has no business
    -- publishing a brand event, and the constraint is what says so.
    CONSTRAINT ck_outbox_event_type_known
        CHECK (event_type IN ('PaymentConfirmed', 'OrderCancelled'))
);

-- The relay reads only unpublished rows, so published ones are never indexed.
-- The index therefore stays small however many rows accumulate, unlike an
-- index over (published, created_at) covering the whole table. The relay
-- claims rows with FOR UPDATE SKIP LOCKED.
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
