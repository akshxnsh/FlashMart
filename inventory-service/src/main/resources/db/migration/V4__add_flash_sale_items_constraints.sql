ALTER TABLE flash_sale_items
    ADD CONSTRAINT ck_flash_sale_items_available_stock_within_total
        CHECK (available_stock <= total_stock);

ALTER TABLE flash_sale_items
    ADD CONSTRAINT ck_flash_sale_items_total_stock_positive
        CHECK (total_stock > 0);

ALTER TABLE flash_sale_items
    ADD CONSTRAINT ck_flash_sale_items_sale_price_non_negative
        CHECK (sale_price >= 0);
