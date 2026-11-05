package com.flashmart.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The numbers the create path is derived from (decisions.md, "Order creation:
 * retries, conflicts, deadlines and Inventory failures", point 7). All
 * PROVISIONAL; see application.yml.
 *
 * <ul>
 *   <li>{@code maxLines = floor(maxBlocking / reserveTimeout)}: a crashed order
 *       can never block its buyer for longer than the policy says.</li>
 *   <li>{@code expires_at = clock_timestamp() + reserveTimeout * lines + margin}:
 *       a healthy loop that hits the timeout on every item still finishes
 *       before the deadline.</li>
 * </ul>
 * Day 5 multiplies the per-call term by the retry attempts.
 */
@Component
public class CreateOrderSettings {

    private final long reserveTimeoutMs;
    private final long maxBlockingMs;
    private final long marginMs;

    public CreateOrderSettings(@Value("${inventory.reserve-timeout-ms}") long reserveTimeoutMs,
                               @Value("${order.create.max-blocking-time-ms}") long maxBlockingMs,
                               @Value("${order.create.deadline-margin-ms}") long marginMs) {
        if (reserveTimeoutMs <= 0 || maxBlockingMs < reserveTimeoutMs || marginMs < 0) {
            throw new IllegalStateException("bad create-order timing config: reserve=" + reserveTimeoutMs
                    + " ms, max-blocking=" + maxBlockingMs + " ms, margin=" + marginMs + " ms");
        }
        this.reserveTimeoutMs = reserveTimeoutMs;
        this.maxBlockingMs = maxBlockingMs;
        this.marginMs = marginMs;
    }

    /** 30 with the current config (60 s / 2 s). */
    public int maxLines() {
        return (int) (maxBlockingMs / reserveTimeoutMs);
    }

    /** The {@code CREATED} deadline's length for an order of {@code lines} items, in seconds. */
    public double deadlineSeconds(int lines) {
        return (reserveTimeoutMs * (double) lines + marginMs) / 1000.0;
    }
}
