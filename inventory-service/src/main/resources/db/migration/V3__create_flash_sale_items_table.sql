CREATE TABLE flash_sale_items (
    id BIGSERIAL PRIMARY KEY,
    flash_sale_id BIGINT NOT NULL REFERENCES flash_sales (id),
    product_id BIGINT NOT NULL REFERENCES products (id),
    sale_price DECIMAL(12, 2) NOT NULL,
    total_stock INTEGER NOT NULL,
    available_stock INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_flash_sale_items_sale_id_product_id UNIQUE (flash_sale_id, product_id),
    CONSTRAINT ck_flash_sale_items_available_stock_non_negative CHECK (available_stock >= 0)
);
