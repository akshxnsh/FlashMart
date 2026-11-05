package com.flashmart.order.client.payment;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/** Body of the gateway's initiate. {@code amount} crosses the wire as a JSON string, never a number. */
public record InitiatePaymentRequest(
        long orderId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency
) {
}
