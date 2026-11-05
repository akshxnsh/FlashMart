package com.flashmart.order.client.payment;

/**
 * The gateway answered 4xx: it understood the request and refused it. Never
 * retried, never counted by the breaker. Almost always a bug on our side (a
 * malformed request, a reused key), so it is logged at ERROR by the caller.
 */
public class PaymentRejectedException extends RuntimeException {

    public PaymentRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
