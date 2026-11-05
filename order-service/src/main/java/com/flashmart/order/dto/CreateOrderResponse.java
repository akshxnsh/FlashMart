package com.flashmart.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * 201 (new order) or 200 (replay) body. {@code amount} is a JSON string, never a
 * number, and is absent until every item is priced.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateOrderResponse(
        Long orderId,
        String status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount
) {
}
