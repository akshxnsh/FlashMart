package com.flashmart.order.entity;

/**
 * The seven states of architecture.md section 6: two transient
 * ({@code CREATED}, {@code PENDING_PAYMENT}) and five terminal.
 *
 * <p>Stored as text with a matching {@code CHECK} in V2, so a value absent
 * from this enum cannot reach the table. {@code CONFIRMED} and
 * {@code RESERVED} were dropped from the original sketch because nothing ever
 * set them; {@code COMPENSATING} was dropped because nothing would ever move
 * an order out of it.
 */
public enum OrderStatus {

    CREATED,
    PENDING_PAYMENT,

    PAID,
    PAYMENT_FAILED,
    ORDER_EXPIRED,
    PAYMENT_UNAVAILABLE,
    RESERVATION_FAILED
}
