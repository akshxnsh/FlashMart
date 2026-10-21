package com.flashmart.inventory.dto.reserve;

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
 */
public record ReserveRequest(
        @NotNull @Positive Long orderId,
        @NotNull @Positive Long flashSaleId,
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity
) {
}
