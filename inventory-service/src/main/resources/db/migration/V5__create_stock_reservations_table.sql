CREATE TABLE stock_reservations (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    flash_sale_item_id BIGINT NOT NULL REFERENCES flash_sale_items (id),
    quantity INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    reserved_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_stock_reservations_order_id_item_id UNIQUE (order_id, flash_sale_item_id),
    CONSTRAINT ck_stock_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_stock_reservations_status CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED'))
);

CREATE INDEX idx_stock_reservations_status_reserved_at ON stock_reservations (status, reserved_at);
