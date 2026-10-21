package com.flashmart.inventory.dto.flashsale;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * Adds a product to a sale. available_stock is not a field: it starts equal to
 * totalStock and moves only through reserve and release, never through an edit.
 */
public record AddSaleItemRequest(
        @NotNull @Positive Long productId,
        // PositiveOrZero matches ck_flash_sale_items_sale_price_non_negative:
        // a free item is allowed. Digits matches sale_price DECIMAL(12, 2).
        // Accepted as "3999.00" or 3999.00; both parse exactly into BigDecimal.
        @NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2)
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salePrice,
        @NotNull @Positive Integer totalStock
) {
}
