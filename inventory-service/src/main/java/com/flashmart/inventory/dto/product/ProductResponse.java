package com.flashmart.inventory.dto.product;

import com.flashmart.inventory.entity.Product;

public record ProductResponse(Long id, String name) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName());
    }
}
