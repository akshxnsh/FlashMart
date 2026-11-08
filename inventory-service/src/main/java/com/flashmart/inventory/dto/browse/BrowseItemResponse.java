package com.flashmart.inventory.dto.browse;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.flashmart.inventory.entity.FlashSaleItem;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One product of a sale as a browsing buyer sees it. {@code inStock} is the only
 * stock information: no {@code availableStock}, and no {@code totalStock} either
 * (the existing {@code FlashSaleItemResponse} carries the total, which is the
 * brand view, so browse does not reuse it). A buyer must not learn how many
 * units are left, or watch the hottest row drain.
 */
public record BrowseItemResponse(
        Long id,
        Long productId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salePrice,
        boolean inStock
) {
    public static BrowseItemResponse from(FlashSaleItem item) {
        return new BrowseItemResponse(
                item.getId(),
                item.getProductId(),
                item.getSalePrice().setScale(2, RoundingMode.UNNECESSARY),
                item.getAvailableStock() > 0);
    }
}
