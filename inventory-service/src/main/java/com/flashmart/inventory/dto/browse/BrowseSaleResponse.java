package com.flashmart.inventory.dto.browse;

import com.flashmart.inventory.dto.flashsale.FlashSaleResponse;
import com.flashmart.inventory.dto.flashsale.FlashSaleState;

import java.time.Instant;
import java.util.List;

/** A sale (the existing {@link FlashSaleResponse} fields, flattened) plus its items. */
public record BrowseSaleResponse(
        Long id,
        String name,
        Instant startTime,
        Instant endTime,
        FlashSaleState state,
        List<BrowseItemResponse> items
) {
    public static BrowseSaleResponse of(FlashSaleResponse sale, List<BrowseItemResponse> items) {
        return new BrowseSaleResponse(sale.id(), sale.name(), sale.startTime(), sale.endTime(), sale.state(), items);
    }
}
