package com.flashmart.order.webhook;

/** What one webhook did. The HTTP status is chosen from this; the body echoes it for logs. */
public enum WebhookOutcome {
    /** 200: this webhook won {@code PENDING_PAYMENT -> PAID} and wrote {@code PaymentConfirmed}. */
    PAID,
    /** 200: the order is already {@code PAID} with this ref; a redelivery, nothing written. */
    ALREADY_PAID,
    /** 200: the payment is not (or no longer) the one this order keeps; a refund row exists for it. */
    REFUND_RECORDED,
    /** 200: this webhook won {@code PENDING_PAYMENT -> PAYMENT_FAILED} and wrote {@code OrderCancelled}. */
    PAYMENT_FAILED,
    /** 200: a {@code FAILED} that matched nothing (other ref, or the order already left PENDING_PAYMENT). */
    IGNORED,
    /** 404: no order with this id. */
    ORDER_NOT_FOUND,
    /** 409: the order is still {@code CREATED}; nothing written, the gateway is expected to retry. */
    TOO_EARLY
}
