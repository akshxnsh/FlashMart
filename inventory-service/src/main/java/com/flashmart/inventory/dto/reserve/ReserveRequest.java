package com.flashmart.inventory.dto.reserve;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /internal/flash-sale-items/reserve}, sent by Order once
 * per order item.
 *
 * <p>The idempotency key is {@code (orderId, flashSaleId, productId)}.
 * {@code quantity} is deliberately NOT part of it: a retry with a different
 * quantity is a bug upstream, answered with {@code 409 QUANTITY_MISMATCH},
 * not a second reservation.
 *
 * <p>{@code quantity} is 1 to {@value #MAX_QUANTITY_PER_ITEM}. Any violation,
 * including the cap, answers {@code 400 INVALID_QUANTITY}.
 */
public record ReserveRequest(
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long flashSaleId,
        @NotNull @Positive Long productId,
        @NotNull @Positive @Max(ReserveRequest.MAX_QUANTITY_PER_ITEM) Integer quantity
) {

    /**
     * A business rule that may change, so it lives here and not in a database
     * CHECK: a CHECK is for what must never be false.
     */
    public static final int MAX_QUANTITY_PER_ITEM = 5;
}
