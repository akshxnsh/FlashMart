package com.flashmart.order.dto;

/** The {@code code} of every non-2xx body of {@code POST /api/orders}. Clients branch on this. */
public enum ErrorCode {
    /** 400: a field, header or body that cannot be accepted. */
    INVALID_REQUEST,
    /** 401: the Gateway's signed buyer identity is missing, wrong, too old or from the future. */
    UNAUTHORIZED,
    /** 400: a quantity outside 1..5, per line or after merging duplicates. */
    INVALID_QUANTITY,
    /** 400: more distinct products than maxLines. */
    TOO_MANY_LINES,
    /** 409: the idempotency key was already used for a different request. */
    IDEMPOTENCY_KEY_REUSED,
    /** 409: the buyer already has a live or paid order for this sale. */
    ORDER_ALREADY_OPEN,
    /** 409: lost a race whose winner is no longer visible; retry with the same key. */
    CONFLICT_RETRY,
    /** 409: an item could not be reserved; the order is RESERVATION_FAILED. */
    RESERVATION_FAILED,
    /** 503: Inventory timed out, refused, or answered non-200; the order is RESERVATION_FAILED. */
    INVENTORY_UNAVAILABLE,
    /** 500: Inventory released a reservation of an order Order still holds as CREATED. */
    INCONSISTENT_STATE,
    /** 503: no payment could be started (retries exhausted or breaker open); the order is PAYMENT_UNAVAILABLE. Retryable with a new key. */
    PAYMENT_UNAVAILABLE,
    /** 500: the payment gateway refused the request (4xx); the order is PAYMENT_UNAVAILABLE. Not retryable. */
    PAYMENT_REJECTED,
    /** 409: the order expired while its payment was being started. */
    ORDER_EXPIRED
}
