package com.flashmart.order.client.inventory;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/**
 * Inventory's 200 body. Only {@code outcome} is always present; the other
 * fields come only with {@code RESERVED}. {@code salePrice} crosses the wire
 * as a JSON string and is read straight into a {@link BigDecimal}, so no
 * floating-point value ever exists on the way.
 */
public record ReserveResponse(
        ReserveOutcome outcome,
        Long reservationId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salePrice,
        Long brandId,
        String productName
) {
}
