package com.flashmart.inventory.dto.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No brandId: the owning brand comes from the authenticated principal, never
 * from the body, so one brand cannot create a product under another's id.
 */
public record CreateProductRequest(
        // 255 matches products.name VARCHAR(255); ck_products_name_present is the backstop.
        @NotBlank @Size(max = 255) String name
) {
}
