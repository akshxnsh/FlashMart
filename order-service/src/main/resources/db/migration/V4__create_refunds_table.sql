-- A command outbox, not an event outbox: the only consumer is Order itself.
-- Refunds are never issued inline -- the webhook handler writes a row here in
-- the same local transaction as its failed CAS, and a worker drains it. That
-- is what makes the one-payment invariant survive a crash mid-refund.
CREATE TABLE refunds (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders (id),
    -- Taken from the webhook, never from orders.payment_ref, which may be NULL
    -- if Order crashed before saving it.
    payment_ref VARCHAR(255) NOT NULL,
    -- From the webhook's reported amount: we refund what was actually
    -- captured, which is not necessarily what the order says it costs.
    amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- attempts + last_error so one permanently unrefundable payment cannot
    -- stall every refund behind it. Same shape as the outbox and
    -- notification_log.
    attempts INTEGER NOT NULL DEFAULT 0,
    -- Both written by the database clock inside the claim SQL, never by Java:
    -- the worker compares them against the database's own now().
    next_retry_at TIMESTAMPTZ,
    -- Claim lease AND fence token: the worker's finishing write is conditional
    -- on the claimed_at it saw, so a worker whose lease expired mid-call
    -- cannot overwrite the result of the worker that took over.
    claimed_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- Per payment, not per order: an order can have several captured payments
    -- (that is the problem refunds exist to correct), but each is refunded
    -- once. This is the idempotency guard for the whole refund path -- a
    -- redelivered webhook tries to insert the same payment_ref and loses.
    CONSTRAINT uk_refunds_payment_ref UNIQUE (payment_ref),
    CONSTRAINT ck_refunds_status_known CHECK (status IN (
        'PENDING',
        'PROCESSING',
        'SUCCEEDED',
        'NEEDS_MANUAL_REVIEW'
    )),
    -- Strictly positive: a zero-amount refund is a no-op row that a worker
    -- would still call the gateway for.
    CONSTRAINT ck_refunds_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_refunds_attempts_non_negative CHECK (attempts >= 0)
);

-- The worker claims by (status, next_retry_at): PENDING rows whose retry time
-- has passed.
CREATE INDEX idx_refunds_claim ON refunds (status, next_retry_at);

-- Same reason as orders: the claim and the finishing write are both raw
-- conditional SQL, which Hibernate's @UpdateTimestamp never sees.
CREATE TRIGGER trg_refunds_bump_updated_at
    BEFORE UPDATE ON refunds
    FOR EACH ROW
    EXECUTE FUNCTION bump_updated_at();
