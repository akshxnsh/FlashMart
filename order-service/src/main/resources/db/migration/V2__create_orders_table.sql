-- Order Service is the sole authority on an order's fate: every write to
-- status is a compare-and-set, and this table is the single row those races
-- resolve on. See architecture.md section 6.
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    -- buyer_id and flash_sale_id are cross-service ids, so no FK is possible:
    -- Identity and Inventory own those rows in their own databases.
    -- flash_sale_id is order-level because one order is exactly one brand's sale.
    buyer_id BIGINT NOT NULL,
    flash_sale_id BIGINT NOT NULL,
    -- SUM(order_items.subtotal), stored rather than derived: it is the price
    -- the buyer agreed to, frozen. NULL until the last item is priced, which
    -- is why the amount guard below is conditional on status rather than a
    -- plain NOT NULL.
    amount DECIMAL(12, 2),
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    -- NULL until initiate is saved. This is what the one-payment CAS matches
    -- on -- it is NOT how a webhook finds its order. The webhook carries our
    -- order id and looks up by primary key, because payment_ref is NULL in
    -- exactly the crash window a webhook is most likely to arrive in.
    payment_ref VARCHAR(255),
    -- NOT NULL, and deliberately WITHOUT a DEFAULT. A default would hard-code
    -- a deadline here, and the deadline values are still open (they must be
    -- derived from the Feign/Resilience4j timeouts they race). SQL writes this
    -- as now() + interval, so the database clock decides, not a pod's clock.
    --
    -- NULL would be worse than wrong: a NULL deadline is never "less than
    -- now()", so the row would be invisible to the expiry sweep and hold its
    -- stock forever.
    --
    -- One column means two things by status: it bounds the reserve loop while
    -- CREATED, then is reset to the payment window on entering
    -- PENDING_PAYMENT. One column, because an order has exactly one live
    -- deadline; the move is ONE statement (status + payment_ref + expires_at),
    -- so there is no gap in which the sweep could expire a healthy order.
    expires_at TIMESTAMPTZ NOT NULL,
    -- Debug only. Never read to drive state -- status is the only authority.
    failure_detail TEXT,
    -- Set once the reconciliation sweep has settled this order (DB clock, in
    -- the sweep's own UPDATE). Bounds the scan and makes it idempotent.
    payment_reconciled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_orders_buyer_id_idempotency_key UNIQUE (buyer_id, idempotency_key),
    -- The seven states of the machine in architecture.md section 6. Pinned for
    -- the same reason Identity pinned event_type in its V7: a typo would
    -- otherwise store a status no CAS ever matches, stranding the order in a
    -- state nothing can move it out of, silently.
    CONSTRAINT ck_orders_status_known CHECK (status IN (
        'CREATED',
        'PENDING_PAYMENT',
        'PAID',
        'PAYMENT_FAILED',
        'ORDER_EXPIRED',
        'PAYMENT_UNAVAILABLE',
        'RESERVATION_FAILED'
    )),
    -- An order can only be payable or paid once it knows what it costs.
    CONSTRAINT ck_orders_amount_present_when_payable
        CHECK (status NOT IN ('PENDING_PAYMENT', 'PAID') OR amount IS NOT NULL),
    -- Nullable, so the guard has to let NULL through explicitly; a bare
    -- `amount >= 0` would evaluate to NULL and pass for an unpriced order
    -- anyway, but spelling it out keeps the intent readable.
    CONSTRAINT ck_orders_amount_non_negative CHECK (amount IS NULL OR amount >= 0)
);

-- A safety net, not a lookup path: one payment must never belong to two
-- orders, and this makes that a database fact rather than an assumption,
-- matching refunds.uk_refunds_payment_ref.
--
-- Partial is NOT needed for correctness. Postgres treats NULLs in a unique
-- index as distinct, so a plain UNIQUE (payment_ref) would already allow
-- unlimited orders sitting at NULL before initiate is saved. (Only
-- UNIQUE NULLS NOT DISTINCT, or a database that folds NULLs together, would
-- behave otherwise.) It is partial for two smaller reasons: a B-tree indexes
-- NULL keys, so a total index would carry an entry per order forever, and
-- most orders never acquire a ref at all -- measured at 90% NULL, the total
-- index was 7x the size of the partial one. And it says in the schema which
-- rows can actually collide. Being partial is also why this is a
-- CREATE INDEX: a table constraint cannot carry a WHERE clause.
--
-- Consequence worth knowing before trusting a green suite: partial and total
-- behave identically here, so NO test can distinguish them. Converting this
-- to a total UNIQUE fails nothing. That is untestable by nature, not by
-- omission.
CREATE UNIQUE INDEX uk_orders_payment_ref ON orders (payment_ref)
    WHERE payment_ref IS NOT NULL;

-- The expiry sweep reads (status, expires_at); the reconciliation sweep reads
-- (status, payment_reconciled_at). Status leads both because it is the
-- selective half -- each sweep wants one or two states out of seven.
CREATE INDEX idx_orders_expiry_sweep ON orders (status, expires_at);
CREATE INDEX idx_orders_reconciliation_sweep ON orders (status, payment_reconciled_at);

-- A trigger, not Hibernate's @UpdateTimestamp, because every status write is
-- raw CAS SQL that bypasses Hibernate's dirty-checking entirely. A Java-side
-- updated_at would silently stop moving on the exact writes that matter most.
-- A trigger cannot be forgotten by a new code path.
CREATE TRIGGER trg_orders_bump_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION bump_updated_at();
