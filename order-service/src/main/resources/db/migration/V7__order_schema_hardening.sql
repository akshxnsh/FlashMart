-- Hardening from an independent static review of V1-V6 (2026-10-29). The
-- reviewer could not run the tests. V1-V6 are applied and never edited, so two
-- corrections to claims made in V2's comments are recorded here instead.
--
-- Correction 1 (V2, on uk_orders_payment_ref): V2 says "NO test can
-- distinguish" the partial index from a total UNIQUE (payment_ref). That is
-- true of behaviour only: both accept many NULLs and reject a duplicate ref.
-- It is false of structure. pg_indexes / pg_index carry the predicate, so a
-- test can see whether the index is partial, and OrderSchemaHardeningTest does.
--
-- Correction 2 (V2, "measured at 90% NULL, the total index was 7x the size of
-- the partial one"): no script in the repo shows how that was measured. Treat
-- it as an expectation (a B-tree stores a NULL key, so a total index carries an
-- entry for every order), not a measurement.

-- 1. A status written without the ref leaves an order no webhook can ever pay:
-- the one-payment CAS matches on payment_ref (architecture.md section 6), so
-- PENDING_PAYMENT or PAID with a NULL ref can never be reached by it.
ALTER TABLE orders ADD CONSTRAINT ck_orders_payment_ref_present_when_payable
    CHECK (status NOT IN ('PENDING_PAYMENT', 'PAID') OR payment_ref IS NOT NULL);

-- 2. NOT NULL cannot stop '' or '   '. A blank idempotency key would make
-- every blank-key retry from one buyer collide on uk_orders_buyer_id_idempotency_key
-- and a blank payment_ref could never match a webhook. NULL stays allowed on
-- orders.payment_ref: it is NULL until initiate is saved.
ALTER TABLE orders ADD CONSTRAINT ck_orders_idempotency_key_present
    CHECK (length(btrim(idempotency_key)) > 0);
ALTER TABLE orders ADD CONSTRAINT ck_orders_payment_ref_present
    CHECK (payment_ref IS NULL OR length(btrim(payment_ref)) > 0);
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_payment_ref_present
    CHECK (length(btrim(payment_ref)) > 0);

-- 3. subtotal = unit_price * quantity and amount = SUM(subtotal), so both can
-- exceed unit_price's 12,2 range. unit_price stays 12,2. Widening only: every
-- existing CHECK is kept and none needs rewriting (Postgres rebuilds the ones
-- that mention these columns).
ALTER TABLE orders ALTER COLUMN amount TYPE NUMERIC(14, 2);
ALTER TABLE order_items ALTER COLUMN subtotal TYPE NUMERIC(14, 2);
ALTER TABLE refunds ALTER COLUMN amount TYPE NUMERIC(14, 2);
