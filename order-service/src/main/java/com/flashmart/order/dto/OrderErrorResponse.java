package com.flashmart.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Body of every non-2xx response. {@code code} is the contract; {@code message}
 * is for people and logs. The order fields are present only when an order is
 * involved (for example the existing order on {@code ORDER_ALREADY_OPEN}).
 * {@code reason} is the reserve outcome that failed an order, and only on the
 * first response: a replay of a failed order carries no reason.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderErrorResponse(
        ErrorCode code,
        String message,
        Long orderId,
        String status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String reason
) {

    public static OrderErrorResponse of(ErrorCode code, String message) {
        return new OrderErrorResponse(code, message, null, null, null, null);
    }
}
