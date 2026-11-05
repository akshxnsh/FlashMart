-- decisions.md, "Day 7", point 7 (A3, expiry-sweep part). V2 is applied and
-- is never edited; this replaces its expiry index.
--
-- The expiry sweep only ever reads live orders (CREATED, PENDING_PAYMENT),
-- and almost every row ends terminal. A partial index holds only the rows the
-- sweep can pick, so it stays small as terminal rows pile up. The size gain is
-- UNMEASURED; at today's sizes either index is fast.
--
-- Postgres uses a partial index only when it can prove the query's WHERE
-- implies the index predicate at plan time. The sweep SELECT must therefore
-- spell these two statuses as LITERALS, not as a bind parameter.
--
-- idx_orders_reconciliation_sweep (status, payment_reconciled_at) is left
-- alone: the refund/reconciliation part of A3 is not decided.
CREATE INDEX idx_orders_expiry_sweep_live ON orders (expires_at)
    WHERE status IN ('CREATED', 'PENDING_PAYMENT');

DROP INDEX idx_orders_expiry_sweep;
