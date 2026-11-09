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
 * <p><b>Cursor (addendum, decisions.md).</b> Rows answered "live", 404 or an
 * unknown status are not acted on, so they stay {@code RESERVED} and stay the
 * oldest; reading "the 50 oldest" every pass would let 50 of them hide every
 * releasable row behind them, and one order that always fails would stop every
 * pass at the same place. So each pass reads the next stale rows AFTER an
 * in-memory keyset cursor {@code (reserved_at, id)}, oldest first:
 * <ul>
 *   <li>a full batch with every order answered: the cursor moves to the batch's
 *       last row;</li>
 *   <li>fewer rows than the batch (the end was reached): the cursor goes back to
 *       the start, so the next pass wraps around to the oldest rows again;</li>
 *   <li>Order unreachable: the pass stops, and the cursor moves PAST the failed
 *       order's rows in this batch, so the next pass starts after it. Trade-off:
 *       an order that failed only once (and any row read before its last row and
 *       not yet asked about) waits until the next wrap-around. That only delays;
 *       nothing is acted on without a terminal answer.</li>
 * </ul>
 * Memory only: a restart starts again from the oldest row. The cursor decides
 * only which rows are LOOKED AT, never what is done to them.
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
    static final List<String> ALERT_REASONS = List.of("order_live", "unknown_status", "order_not_found",
            "order_unreachable");
    static final List<String> ACTIONS = List.of("confirmed", "released");

    /** A row's position in the scan order. */
    record Key(java.time.OffsetDateTime reservedAt, long id) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int c = reservedAt.compareTo(o.reservedAt);
            return c != 0 ? c : Long.compare(id, o.id);
        }
    }

    private record Row(long orderId, Key key) {
    }

    /** Stale RESERVED rows; the threshold is measured on the database clock. Index (status, reserved_at). */
    private static final String STALE = """
            SELECT order_id, reserved_at, id
              FROM stock_reservations
             WHERE status = 'RESERVED'
               AND reserved_at < now() - make_interval(secs => ?)
            """;

    private static final org.springframework.jdbc.core.RowMapper<Row> ROW = (rs, i) -> new Row(rs.getLong("order_id"),
            new Key(rs.getObject("reserved_at", java.time.OffsetDateTime.class), rs.getLong("id")));

    /** What one pass did. {@code stoppedAt} is the order whose call failed, or null. */
    public record Result(int rows, int ordersAsked, int confirmed, int released, int alerts, Long stoppedAt) {
    }

    private final JdbcTemplate jdbc;
    private final OrderStatusClient order;
    private final ReservationEventService events;
    private final ReconcilerSettings settings;
    private final MeterRegistry meters;

    /** Where the next pass starts (exclusive); null = from the oldest stale row. One pass at a time. */
    private Key cursor;

    public StaleReservationReconciler(JdbcTemplate jdbc, OrderStatusClient order, ReservationEventService events,
                                      ReconcilerSettings settings, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.order = order;
        this.events = events;
        this.settings = settings;
        this.meters = meters;
        // Registered at zero, so Prometheus shows every series from the first scrape.
        ALERT_REASONS.forEach(r -> count("inventory.reconciler.alerts", "reason", r, 0));
        ACTIONS.forEach(a -> count("inventory.reconciler.actions", "action", a, 0));
    }

    public synchronized Result runOnce() {
        Key after = cursor;
        double thresholdSeconds = settings.thresholdMs() / 1000.0;
        List<Row> rows = after == null
                ? jdbc.query(STALE + "ORDER BY reserved_at, id LIMIT ?", ROW, thresholdSeconds, settings.batchSize())
                : jdbc.query(STALE + "AND (reserved_at, id) > (?, ?) ORDER BY reserved_at, id LIMIT ?", ROW,
                        thresholdSeconds, after.reservedAt(), after.id(), settings.batchSize());
        List<Long> orderIds = new ArrayList<>(new LinkedHashSet<>(rows.stream().map(Row::orderId).toList()));

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
                            + "). Nothing changed; this pass stops, the next one starts after this order.");
                    cursor = rows.stream().filter(r -> r.orderId() == orderId).map(Row::key)
                            .max(Key::compareTo).orElseThrow();
                    return new Result(rows.size(), asked, confirmed, released, alerts, orderId);
                }
            }
        }
        // End reached: wrap around next pass. Otherwise continue after this batch.
        cursor = rows.size() < settings.batchSize() ? null : rows.getLast().key();
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
