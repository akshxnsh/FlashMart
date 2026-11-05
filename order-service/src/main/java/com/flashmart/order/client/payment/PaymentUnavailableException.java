package com.flashmart.order.client.payment;

/**
 * No payment could be started: retries exhausted on timeouts, connect errors
 * or 5xx, the circuit breaker is open, or a 2xx came back unusable. A payment
 * may still exist at the gateway (a timed-out call can succeed there); the
 * Day 6 webhook handler is what catches that. The message goes into
 * {@code orders.failure_detail} (debug only).
 */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
