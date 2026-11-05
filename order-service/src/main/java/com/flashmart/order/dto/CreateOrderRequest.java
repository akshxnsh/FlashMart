package com.flashmart.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Body of {@code POST /api/orders}. One order is one flash sale.
 *
 * <p>The per-line quantity cap (1..{@value #MAX_QUANTITY_PER_ITEM}) is checked
 * here, before any call to Inventory, so a buyer's mistake is a 400 from Order
 * and never an alert in Inventory. Duplicate product ids are merged by the
 * service, and the cap is checked again on the merged quantity.
 */
public record CreateOrderRequest(
        @NotNull @Positive Long flashSaleId,
        @NotEmpty List<@NotNull @Valid Item> items
) {

    /** Same value as Inventory's {@code ReserveRequest.MAX_QUANTITY_PER_ITEM}. */
    public static final int MAX_QUANTITY_PER_ITEM = 5;

    public record Item(
            @NotNull @Positive Long productId,
            @NotNull @Min(1) @Max(MAX_QUANTITY_PER_ITEM) Integer quantity
    ) {
    }
}
