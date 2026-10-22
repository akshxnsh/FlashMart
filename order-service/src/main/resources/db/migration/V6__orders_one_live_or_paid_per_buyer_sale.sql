-- A buyer may hold at most one order in CREATED, PENDING_PAYMENT or PAID per
-- flash sale. Any other status (PAYMENT_FAILED, ORDER_EXPIRED,
-- PAYMENT_UNAVAILABLE, RESERVATION_FAILED) is outside the rule, so a buyer
-- whose order failed can try again.
--
-- It is a unique index in the database, not a check in code: two tabs
-- submitted together both pass a "does this buyer already have an order?"
-- read, so only the database can decide which insert wins.
--
-- Why not PAID only: a buyer could open many orders and hold stock for the
-- whole payment window, and a second payment would be taken before the unique
-- rule fired. Partial, because a plain UNIQUE (buyer_id, flash_sale_id) would
-- also block a retry after a failure. Being partial is why this is a
-- CREATE INDEX: a table constraint cannot carry a WHERE clause.
CREATE UNIQUE INDEX uk_orders_buyer_sale_live_or_paid ON orders (buyer_id, flash_sale_id)
    WHERE status IN ('CREATED', 'PENDING_PAYMENT', 'PAID');
