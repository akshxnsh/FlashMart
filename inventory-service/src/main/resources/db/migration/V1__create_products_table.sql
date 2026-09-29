CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    brand_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_products_brand_id_name UNIQUE (brand_id, name)
);
