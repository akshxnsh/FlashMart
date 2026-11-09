package com.flashmart.inventory.reconciler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The stale-reservation reconciler's numbers (decisions.md, "Day 9: Inventory
 * stale-reservation reconciler"). All PROVISIONAL and unmeasured; see
 * application.yml.
 *
 * <p>The age threshold is DERIVED, not picked: a {@code RESERVED} row is stale
 * only once no healthy path could still settle it. Its order can stay
 * {@code CREATED} for at most Order's longest {@code CREATED} deadline, then
 * {@code PENDING_PAYMENT} for at most the payment window; Order's sweep may run
 * up to one interval late to end it; then the {@code OrderCancelled} or
 * {@code PaymentConfirmed} event still has to reach Inventory (Kafka drain
 * margin). So {@code threshold = paymentWindow + maxCreatedDeadline +
 * sweepInterval + drainMargin} = 360 + 60.5 + 30 + 120 = 570.5 s with the
 * current values. The first three MIRROR Order's own values (Order's
 * {@code order.payment-window-ms}, {@code CreateOrderSettings.deadlineSeconds(maxLines)}
 * and {@code order.sweep.interval-ms}); nothing checks that they stay equal, so a
 * change in Order must be copied here.
 */
@Component
public class ReconcilerSettings {

    private final int batchSize;
    private final long thresholdMs;
    private final String orderBaseUrl;
    private final long connectTimeoutMs;
    private final long readTimeoutMs;

    public ReconcilerSettings(@Value("${reconciler.batch-size}") int batchSize,
                              @Value("${reconciler.threshold.order-payment-window-ms}") long paymentWindowMs,
                              @Value("${reconciler.threshold.order-max-created-deadline-ms}") long maxCreatedDeadlineMs,
                              @Value("${reconciler.threshold.order-sweep-interval-ms}") long sweepIntervalMs,
                              @Value("${reconciler.threshold.kafka-drain-margin-ms}") long drainMarginMs,
                              @Value("${reconciler.order.base-url}") String orderBaseUrl,
                              @Value("${reconciler.order.connect-timeout-ms}") long connectTimeoutMs,
                              @Value("${reconciler.order.read-timeout-ms}") long readTimeoutMs) {
        if (batchSize < 1 || paymentWindowMs < 0 || maxCreatedDeadlineMs < 0 || sweepIntervalMs < 0
                || drainMarginMs < 0 || connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalStateException("bad reconciler config");
        }
        this.batchSize = batchSize;
        this.thresholdMs = paymentWindowMs + maxCreatedDeadlineMs + sweepIntervalMs + drainMarginMs;
        this.orderBaseUrl = orderBaseUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Most reservation rows looked at in one pass (50). */
    public int batchSize() {
        return batchSize;
    }

    /** How old a RESERVED row must be before Order is asked about it (570,500 ms). */
    public long thresholdMs() {
        return thresholdMs;
    }

    public String orderBaseUrl() {
        return orderBaseUrl;
    }

    public long connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public long readTimeoutMs() {
        return readTimeoutMs;
    }
}
