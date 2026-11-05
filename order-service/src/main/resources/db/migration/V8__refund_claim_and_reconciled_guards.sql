-- Two guards from the independent review of Oct 29, decided on 2026-11-05 in
-- docs/decisions.md, "Order creation: retries, conflicts, deadlines and
-- Inventory failures" (point 10). Both tables held 0 rows that would break
-- either CHECK when this was written (checked against the local database).

-- 1. The refund worker claims a row by CAS PENDING -> PROCESSING and stamps
-- claimed_at in the same statement; its finishing write is fenced on the
-- claimed_at it saw. A PROCESSING row with no claimed_at could never be
-- reclaimed by the stale-claim sweep (NULL is never older than a lease) and
-- could never be finished by the fence, so it would be stuck forever.
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_processing_has_claimed_at
    CHECK (status <> 'PROCESSING' OR claimed_at IS NOT NULL);

-- 2. The reconciliation sweep only settles terminal-failure orders. A stamp on
-- any other status means a bug wrote it, and the sweep would then skip that
-- order forever. RESERVATION_FAILED is included even though no payment can
-- exist for it, so the rule reads "any failure state" and a later scan of it
-- needs no migration.
ALTER TABLE orders ADD CONSTRAINT ck_orders_reconciled_only_when_failed
    CHECK (payment_reconciled_at IS NULL
        OR status IN ('PAYMENT_FAILED', 'PAYMENT_UNAVAILABLE', 'ORDER_EXPIRED', 'RESERVATION_FAILED'));
