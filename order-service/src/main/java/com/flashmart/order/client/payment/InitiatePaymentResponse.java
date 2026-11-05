package com.flashmart.order.client.payment;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/** The gateway's 202 body. Only {@code paymentRef} is used by Order. */
public record InitiatePaymentResponse(
        String paymentRef,
        Long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String status
) {
}
