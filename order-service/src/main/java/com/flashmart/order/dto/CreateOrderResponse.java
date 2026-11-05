package com.flashmart.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 201 (this request started the payment) or 200 (a replay) body. {@code amount}
 * is a JSON string, never a number, and is absent until every item is priced.
 * {@code paymentRef} is present once the payment started; {@code expiresAt} only
 * while the order is PENDING_PAYMENT (the end of the payment window).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateOrderResponse(
        Long orderId,
        String status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String paymentRef,
        Instant expiresAt
) {

    public CreateOrderResponse(Long orderId, String status, BigDecimal amount) {
        this(orderId, status, amount, null, null);
    }
}
