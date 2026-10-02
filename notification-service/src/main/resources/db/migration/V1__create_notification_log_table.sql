CREATE TABLE notification_log (
    id BIGSERIAL PRIMARY KEY,
    -- Order owns `orders`; this is a cross-service id with no FK.
    order_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    recipient_type VARCHAR(20) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- The address actually used, captured at send time. The contact caches hold
    -- only the CURRENT address, so without this the log cannot answer "where did
    -- this go?" the moment a buyer changes their email — which is exactly when
    -- the question gets asked. A log records what happened, not what is now true.
    recipient_address VARCHAR(255),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    -- Why the last attempt failed. Without it, "the mail server is down" and
    -- "I have no contact for this buyer yet" are indistinguishable in the data,
    -- and they call for different responses. Matches refunds and outbox.
    last_error TEXT,

    -- Idempotent consumer for at-least-once Kafka delivery. `reason` is
    -- deliberately NOT part of the key: OrderCancelled can be published twice
    -- for one order (the death path and the late-reservation cleanup both do
    -- it), and the second must be deduplicated rather than send a second email.
    CONSTRAINT uk_notification_log_order_event_recipient_channel
        UNIQUE (order_id, event_type, recipient_type, channel),

    -- Vocabularies pinned, as on outbox.event_type: a typo'd recipient_type
    -- would log a buyer's receipt against the brand and defeat the unique key.
    CONSTRAINT ck_notification_log_event_type
        CHECK (event_type IN ('PaymentConfirmed', 'OrderCancelled')),
    CONSTRAINT ck_notification_log_recipient_type
        CHECK (recipient_type IN ('BUYER', 'BRAND')),
    CONSTRAINT ck_notification_log_channel
        CHECK (channel IN ('EMAIL')),
    CONSTRAINT ck_notification_log_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'RETRYING')),

    CONSTRAINT ck_notification_log_attempts_non_negative CHECK (attempts >= 0),
    -- status and sent_at can never disagree, the same shape as api_keys.revoked_at.
    CONSTRAINT ck_notification_log_sent_at_matches_status
        CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    -- One-directional on purpose: a SENT row must say where it went, but a
    -- FAILED row legitimately may not know (the contact had not arrived yet).
    CONSTRAINT ck_notification_log_sent_rows_have_an_address
        CHECK (status <> 'SENT' OR recipient_address IS NOT NULL)
);

-- The retry worker scans only FAILED rows, and almost every row ends up SENT,
-- so a partial index stays small forever. Same shape as the outbox's.
-- Rows stuck in RETRYING are deliberately not swept: that machinery defends a
-- rare crash case this service does not build.
CREATE INDEX idx_notification_log_retryable
    ON notification_log (next_retry_at) WHERE status = 'FAILED';
