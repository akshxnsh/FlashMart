package com.flashmart.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The payment window: how long a PENDING_PAYMENT order holds its stock and
 * blocks its buyer. A policy, not derivable (decisions.md, Day 5, point 7);
 * PROVISIONAL. Written into {@code expires_at} by the PENDING_PAYMENT CAS.
 */
@Component
public class PaymentSettings {

    private final long paymentWindowMs;

    public PaymentSettings(@Value("${order.payment-window-ms}") long paymentWindowMs) {
        if (paymentWindowMs <= 0) {
            throw new IllegalStateException("order.payment-window-ms must be greater than zero, was "
                    + paymentWindowMs);
        }
        this.paymentWindowMs = paymentWindowMs;
    }

    public long paymentWindowMs() {
        return paymentWindowMs;
    }
}
