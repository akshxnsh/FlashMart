package com.flashmart.inventory.dto.flashsale;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.flashmart.inventory.entity.FlashSaleItem;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deliberately no availableStock (and no version): the live counter of the
 * hottest row is not part of this response.
 */
public record FlashSaleItemResponse(
        Long id,
        Long flashSaleId,
        Long productId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salePrice,
        Integer totalStock
) {

    /** Same normalisation as {@code ReserveResponse.reserved}: scale 2, plain notation, never rounded. */
    public static FlashSaleItemResponse from(FlashSaleItem item) {
        return new FlashSaleItemResponse(
                item.getId(),
                item.getFlashSaleId(),
                item.getProductId(),
                item.getSalePrice().setScale(2, RoundingMode.UNNECESSARY),
                item.getTotalStock());
    }
}
