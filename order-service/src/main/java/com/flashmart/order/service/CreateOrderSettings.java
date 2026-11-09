package com.flashmart.order.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The numbers the create path is derived from (decisions.md, "Day 5: payment
 * step, resilience and derived deadlines", points 1 and 6, and "Day 9, Part F:
 * open-in-view off and the pool wait in the CREATED deadline"). All PROVISIONAL
 * and unmeasured; see application.yml. Every value below comes from config, so
 * the timeouts the client and the pool really use are the ones the deadline is
 * built from.
 *
 * <ul>
 *   <li>{@code perReserveCall = inventory connect + read}: the client's real
 *       ceiling for one reserve call (3 s).</li>
 *   <li>{@code paymentBudget = attempts * (connect + read) + (attempts - 1) * wait}:
 *       the worst case of the payment call with its retries (6.5 s).</li>
 *   <li>{@code poolWait = spring.datasource.hikari.connection-timeout}: the longest
 *       one borrow can wait for a pooled connection before it fails (1 s). With
 *       open-in-view off every short transaction borrows again, so every borrow
 *       between the insert (which stamps {@code expires_at}) and the
 *       {@code PENDING_PAYMENT} CAS can wait this long. Counted from
 *       {@link OrderCreationService}: {@value #BORROWS_BEFORE_LINES} before the
 *       first reserve ({@code unpricedItems}), {@value #BORROWS_PER_LINE} per line
 *       ({@code priceItem}, plus the {@code read} when another loop priced it and
 *       the loop goes on), {@value #BORROWS_AFTER_LINES} after the lines
 *       ({@code setAmountFromItems}, {@code read}, {@code markPendingPayment}).
 *       The insert's own wait comes before {@code expires_at} is stamped, and the
 *       reads after the CAS no longer race the deadline, so neither counts.</li>
 *   <li>{@code perLine = perReserveCall + BORROWS_PER_LINE * poolWait};
 *       {@code fixed = paymentBudget + (BORROWS_BEFORE_LINES + BORROWS_AFTER_LINES) * poolWait}.</li>
 *   <li>{@code maxLines = floor((maxBlocking - fixed) / perLine)}: a crashed
 *       order can never block its buyer for longer than the policy.</li>
 *   <li>{@code expires_at = clock_timestamp() + perLine * lines + fixed + margin}:
 *       a healthy loop that hits the timeout on every item and waits the full
 *       pool timeout on every borrow, plus the payment call, still finishes
 *       before the deadline.</li>
 * </ul>
 * Not covered (accepted): a borrow that FAILS after waiting does not count as
 * finishing; the request answers 503 and the order is left to the sweep.
 */
@Component
public class CreateOrderSettings {

    /** Borrows after the insert and before the first reserve call: {@code unpricedItems}. */
    static final int BORROWS_BEFORE_LINES = 1;
    /** Worst case per line that lets the loop go on: {@code priceItem} returns 0, then {@code read}. */
    static final int BORROWS_PER_LINE = 2;
    /** After the lines, up to the CAS: {@code setAmountFromItems}, {@code read}, {@code markPendingPayment}. */
    static final int BORROWS_AFTER_LINES = 3;

    private final long perReserveCallMs;
    private final long paymentBudgetMs;
    private final long poolWaitMs;
    private final long perLineMs;
    private final long fixedMs;
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
                               @Value("${order.fee}") BigDecimal fee,
                               @Value("${spring.datasource.hikari.connection-timeout}") long poolWaitMs) {
        if (inventoryConnectMs <= 0 || inventoryReadMs <= 0 || paymentConnectMs <= 0 || paymentReadMs <= 0
                || paymentAttempts < 1 || paymentWaitMs < 0 || marginMs < 0 || poolWaitMs <= 0) {
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
        this.poolWaitMs = poolWaitMs;
        this.perLineMs = perReserveCallMs + BORROWS_PER_LINE * poolWaitMs;
        this.fixedMs = paymentBudgetMs + (BORROWS_BEFORE_LINES + BORROWS_AFTER_LINES) * poolWaitMs;
        this.maxBlockingMs = maxBlockingMs;
        this.marginMs = marginMs;
        this.fee = fee;
        if (maxBlockingMs - fixedMs < perLineMs) {
            throw new IllegalStateException("max-blocking-time " + maxBlockingMs + " ms leaves no room for one line: "
                    + "payment budget + pool waits " + fixedMs + " ms, per line " + perLineMs + " ms");
        }
    }

    /** 9 with the current config: floor((60 s - 6.5 s - 4 x 1 s) / (3 s + 2 x 1 s)). Was 17 before the pool wait. */
    public int maxLines() {
        return (int) ((maxBlockingMs - fixedMs) / perLineMs);
    }

    /** The {@code CREATED} deadline's length for an order of {@code lines} items, in seconds. */
    public double deadlineSeconds(int lines) {
        return (perLineMs * (double) lines + fixedMs + marginMs) / 1000.0;
    }

    /** The pool's connection-timeout this deadline was built from, in ms (one source: the Hikari property). */
    public long poolWaitMs() {
        return poolWaitMs;
    }

    /** The flat fee added to every order's amount. */
    public BigDecimal fee() {
        return fee;
    }
}
