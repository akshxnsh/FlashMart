package com.flashmart.order.webhook;

import java.math.BigDecimal;

/**
 * A verified, parsed payment webhook: {@code {order_id, payment_ref, status,
 * amount}} (decisions.md, "Day 6", point 4). The order is found by
 * {@code orderId}, never by {@code paymentRef}; the ref and amount are what the
 * CAS matches, and what a refund is written with.
 */
public record PaymentWebhook(long orderId, String paymentRef, Status status, BigDecimal amount) {

    public enum Status {
        SUCCESS,
        FAILED
    }
}
