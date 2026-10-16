package com.flashmart.order.entity;

/**
 * The refund worker's claim states. {@code PENDING -> PROCESSING} is a CAS,
 * and {@code claimed_at} fences the finishing write, so a worker whose lease
 * expired mid-call cannot overwrite the worker that took over.
 *
 * <p>{@code NEEDS_MANUAL_REVIEW} is the terminal state for a payment the
 * gateway will never refund: without it, such a row would be retried forever
 * and hold up every refund behind it.
 */
public enum RefundStatus {

    PENDING,
    PROCESSING,
    SUCCEEDED,
    NEEDS_MANUAL_REVIEW
}
