package com.flashmart.inventory.reconciler;

import com.flashmart.inventory.service.ReservationEventService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Inventory's stale-reservation reconciler (architecture.md §3, "Inventory →
 * Order"; decisions.md, "Day 9: Inventory stale-reservation reconciler"). It
 * closes the Version B gap (decisions.md, "Day 7", point 5): a reserve that
 * timed out in Order but committed in Inventory after Inventory had already
 * handled the order's {@code OrderCancelled} leaves a {@code RESERVED} row that
 * no event will ever release.
 *
 * <p>One pass:
 * <ol>
 *   <li>Read up to {@code batch-size} {@code RESERVED} rows older than the derived
 *       threshold, oldest first (index {@code (status, reserved_at)}), and take
 *       their distinct order ids. One short read; no transaction stays open.</li>
 *   <li>For each order, ask Order, with NO transaction and NO connection held
 *       during the call.</li>
 *   <li>Act only on a TERMINAL answer, through the existing status-guarded code,
 *       each in its own short transaction: {@code PAID} → {@link
 *       ReservationEventService#confirm}; one of the four failure states → {@link
 *       ReservationEventService#release} (stock goes back only for rows that
 *       actually flipped). Every other answer changes NOTHING: {@code CREATED} /
 *       {@code PENDING_PAYMENT} (an order this old should have been ended by
 *       Order's sweep), {@code 404}, an unknown status, or Order unreachable.
 *       Each is an ERROR log with the order id plus a counter tagged by reason.
 *       Inventory never guesses.</li>
 *   <li>The pass STOPS at the first unreachable Order (no hammering a sick
 *       service); the next pass retries. A {@code 404} or a live order is an
 *       answer, not a failure, so the pass goes on.</li>
 * </ol>
 *
 * <p>No lock is needed. Order's states {@code PAID} and the four failures have
 * no way out (every Order status write requires {@code CREATED} or
 * {@code PENDING_PAYMENT}), so an answer acted on cannot change after it was
 * read. A race with the Kafka consumers on the same rows is safe because both
 * sides use the same {@code UPDATE ... WHERE status = 'RESERVED'}: the loser
 * matches 0 rows and adds 0 stock (raced in a test).
 */
@Slf4j
@Component
public class StaleReservationReconciler {

    static final Set<String> FAILED = Set.of("RESERVATION_FAILED", "PAYMENT_UNAVAILABLE", "ORDER_EXPIRED",
            "PAYMENT_FAILED");
    static final Set<String> LIVE = Set.of("CREATED", "PENDING_PAYMENT");

    /** What one pass did. {@code stoppedAt} is the order whose call failed, or null. */
    public record Result(int rows, int ordersAsked, int confirmed, int released, int alerts, Long stoppedAt) {
    }

    private final JdbcTemplate jdbc;
    private final OrderStatusClient order;
    private final ReservationEventService events;
    private final ReconcilerSettings settings;
    private final MeterRegistry meters;

    public StaleReservationReconciler(JdbcTemplate jdbc, OrderStatusClient order, ReservationEventService events,
                                      ReconcilerSettings settings, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.order = order;
        this.events = events;
        this.settings = settings;
        this.meters = meters;
    }

    public Result runOnce() {
        List<Long> rows = jdbc.queryForList("""
                SELECT order_id
                  FROM stock_reservations
                 WHERE status = 'RESERVED'
                   AND reserved_at < now() - make_interval(secs => ?)
                 ORDER BY reserved_at
                 LIMIT ?
                """, Long.class, settings.thresholdMs() / 1000.0, settings.batchSize());
        List<Long> orderIds = new ArrayList<>(new LinkedHashSet<>(rows));

        int asked = 0, confirmed = 0, released = 0, alerts = 0;
        for (long orderId : orderIds) {
            asked++;
            OrderStatusClient.Answer answer = order.statusOf(orderId);
            switch (answer) {
                case OrderStatusClient.Answer.Status s when s.status().equals("PAID") -> {
                    int n = events.confirm(orderId);
                    confirmed += n;
                    count("inventory.reconciler.actions", "action", "confirmed", n);
                    log.warn("reconciler: order {} is PAID; confirmed {} stale reservation(s)", orderId, n);
                }
                case OrderStatusClient.Answer.Status s when FAILED.contains(s.status()) -> {
                    int n = events.release(orderId);
                    released += n;
                    count("inventory.reconciler.actions", "action", "released", n);
                    log.warn("reconciler: order {} is {}; released {} stale reservation(s)", orderId, s.status(), n);
                }
                case OrderStatusClient.Answer.Status s when LIVE.contains(s.status()) -> {
                    alerts++;
                    alert("order_live", orderId, "Order says " + s.status() + " for a reservation older than "
                            + settings.thresholdMs() + " ms; Order's expiry sweep may not be running. Nothing changed.");
                }
                case OrderStatusClient.Answer.Status s -> {
                    alerts++;
                    alert("unknown_status", orderId, "Order answered an unknown status '" + s.status()
                            + "'. Nothing changed.");
                }
                case OrderStatusClient.Answer.NotFound nf -> {
                    alerts++;
                    alert("order_not_found", orderId, "Order has no such order (404). Nothing changed.");
                }
                case OrderStatusClient.Answer.Unreachable u -> {
                    alerts++;
                    alert("order_unreachable", orderId, "could not ask Order (" + u.why()
                            + "). Nothing changed; this pass stops, the next one retries.");
                    return new Result(rows.size(), asked, confirmed, released, alerts, orderId);
                }
            }
        }
        return new Result(rows.size(), asked, confirmed, released, alerts, null);
    }

    private void alert(String reason, long orderId, String message) {
        count("inventory.reconciler.alerts", "reason", reason, 1);
        log.error("reconciler: stale RESERVED reservation for order {}: {}", orderId, message);
    }

    private void count(String name, String tag, String value, int amount) {
        Counter.builder(name).tag(tag, value).register(meters).increment(amount);
    }
}
