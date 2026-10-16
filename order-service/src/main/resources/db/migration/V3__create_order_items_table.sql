-- All rows are inserted up front, unpriced, when the order is created. Each
-- row's pricing fields are filled in only when that item's reserve() returns.
-- That is why three columns are nullable on a table that otherwise looks like
-- it should require them.
--
-- No created_at/updated_at: an order item has no lifecycle of its own. It is
-- written once empty and filled once, and the order's own timestamps already
-- bound both. architecture.md section 4 lists exactly these seven columns.
CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    -- Same-service relation, so a real FK. Java still holds a plain Long with
    -- no @ManyToOne, per the entity conventions.
    order_id BIGINT NOT NULL REFERENCES orders (id),
    -- Cross-service id (Inventory owns products), so no FK.
    product_id BIGINT NOT NULL,
    -- Snapshotted at reservation time and never resynced: a receipt shows what
    -- was bought, not what the product is called today.
    product_name VARCHAR(255),
    quantity INTEGER NOT NULL,
    unit_price DECIMAL(12, 2),
    subtotal DECIMAL(12, 2),
    CONSTRAINT uk_order_items_order_id_product_id UNIQUE (order_id, product_id),
    CONSTRAINT ck_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price_non_negative
        CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT ck_order_items_subtotal_non_negative
        CHECK (subtotal IS NULL OR subtotal >= 0),
    -- An item is either entirely unpriced or entirely priced. Without this,
    -- a half-written reserve() result would leave a row that looks priced to
    -- one query and unpriced to another, and orders.amount would be summed
    -- from a subtotal whose unit_price nobody can account for.
    CONSTRAINT ck_order_items_pricing_all_or_none CHECK (
        (product_name IS NULL AND unit_price IS NULL AND subtotal IS NULL)
        OR (product_name IS NOT NULL AND unit_price IS NOT NULL AND subtotal IS NOT NULL)
    ),
    -- subtotal is stored rather than computed, so nothing but this stops it
    -- disagreeing with its own inputs. orders.amount is the sum of these, and
    -- the amount is what the buyer is charged.
    CONSTRAINT ck_order_items_subtotal_matches
        CHECK (subtotal IS NULL OR subtotal = unit_price * quantity),
    -- Not in the section 4 list; carried forward from Inventory's V7
    -- ck_products_name_present. NOT NULL cannot stop '   ', and a blank name
    -- here survives into a permanent record of a completed purchase -- this
    -- column is the receipt. The guard is conditional because an unpriced row
    -- legitimately has no name yet.
    CONSTRAINT ck_order_items_product_name_present
        CHECK (product_name IS NULL OR length(btrim(product_name)) > 0)
);
