CREATE TABLE flash_sales (
    id BIGSERIAL PRIMARY KEY,
    brand_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT ck_flash_sales_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

CREATE INDEX idx_flash_sales_start_end ON flash_sales (start_time, end_time);
