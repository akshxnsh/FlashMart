package com.flashmart.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The numbers the create path is derived from (decisions.md, "Day 5: payment
 * step, resilience and derived deadlines", points 1 and 6). All PROVISIONAL and
 * unmeasured; see application.yml. Every value below comes from config, so the
 * timeouts the client really uses are the timeouts the deadline is built from.
 *
 * <ul>
 *   <li>{@code perReserveCall = inventory connect + read}: the client's real
 *       ceiling for one reserve call (3 s).</li>
 *   <li>{@code paymentBudget = attempts * (connect + read) + (attempts - 1) * wait}:
 *       the worst case of the payment call with its retries (6.5 s).</li>
 *   <li>{@code maxLines = floor((maxBlocking - paymentBudget) / perReserveCall)}:
 *       a crashed order can never block its buyer for longer than the policy.</li>
 *   <li>{@code expires_at = clock_timestamp() + perReserveCall * lines + paymentBudget + margin}:
 *       a healthy loop that hits the timeout on every item, plus the payment
 *       call, still finishes before the deadline.</li>
 * </ul>
 * The payment client itself is not built yet; only its budget is used here.
 */
@Component
public class CreateOrderSettings {

    private final long perReserveCallMs;
    private final long paymentBudgetMs;
    private final long maxBlockingMs;
    private final long marginMs;
    private final BigDecimal fee;

    public CreateOrderSettings(@Value("${inventory.connect-timeout-ms}") long inventoryConnectMs,
                               @Value("${inventory.reserve-timeout-ms}") long inventoryReadMs,
                               @Value("${payment.connect-timeout-ms}") long paymentConnectMs,
                               @Value("${payment.read-timeout-ms}") long paymentReadMs,
                               @Value("${payment.attempts}") int paymentAttempts,
                               @Value("${payment.wait-ms}") long paymentWaitMs,
                               @Value("${order.create.max-blocking-time-ms}") long maxBlockingMs,
                               @Value("${order.create.deadline-margin-ms}") long marginMs,
                               @Value("${order.fee}") BigDecimal fee) {
        if (inventoryConnectMs <= 0 || inventoryReadMs <= 0 || paymentConnectMs <= 0 || paymentReadMs <= 0
                || paymentAttempts < 1 || paymentWaitMs < 0 || marginMs < 0) {
            throw new IllegalStateException("bad create-order timing config");
        }
        // The fee is what makes every payable amount positive (a free item would
        // otherwise give a zero payment). Zero would silently reopen that case.
        if (fee == null || fee.signum() <= 0) {
            throw new IllegalStateException("order.fee must be greater than zero, was " + fee
                    + " (the fee is what keeps every payable amount above zero)");
        }
        this.perReserveCallMs = inventoryConnectMs + inventoryReadMs;
        this.paymentBudgetMs = paymentAttempts * (paymentConnectMs + paymentReadMs)
                + (paymentAttempts - 1) * paymentWaitMs;
        this.maxBlockingMs = maxBlockingMs;
        this.marginMs = marginMs;
        this.fee = fee;
        if (maxBlockingMs - paymentBudgetMs < perReserveCallMs) {
            throw new IllegalStateException("max-blocking-time " + maxBlockingMs + " ms leaves no room for one line: "
                    + "payment budget " + paymentBudgetMs + " ms, per reserve call " + perReserveCallMs + " ms");
        }
    }

    /** 17 with the current config: floor((60 s - 6.5 s) / 3 s). */
    public int maxLines() {
        return (int) ((maxBlockingMs - paymentBudgetMs) / perReserveCallMs);
    }

    /** The {@code CREATED} deadline's length for an order of {@code lines} items, in seconds. */
    public double deadlineSeconds(int lines) {
        return (perReserveCallMs * (double) lines + paymentBudgetMs + marginMs) / 1000.0;
    }

    /** The flat fee added to every order's amount. */
    public BigDecimal fee() {
        return fee;
    }
}
