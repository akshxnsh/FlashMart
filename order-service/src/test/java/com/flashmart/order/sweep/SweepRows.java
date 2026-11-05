package com.flashmart.order.sweep;

import com.flashmart.order.entity.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Orders in a chosen state with a chosen deadline, inserted with native SQL,
 * and the reads the sweep tests assert on. Every order gets an amount (so a
 * CREATED one can move to PENDING_PAYMENT under V2's CHECK); statuses that V7
 * requires to hold a ref get one. No items: the sweep never reads them.
 */
final class SweepRows {

    static final BigDecimal AMOUNT = new BigDecimal("499.00");
    static final long SALE = 7L;

    private final JdbcTemplate jdbc;

    SweepRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code expiresIn} is a Postgres interval relative to now, e.g. {@code "-1 minute"} (due) or {@code "+1 hour"}. */
    long order(long buyer, OrderStatus status, String expiresIn) {
        boolean hasRef = status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.PAID
                || status == OrderStatus.PAYMENT_FAILED || status == OrderStatus.ORDER_EXPIRED;
        return jdbc.queryForObject("INSERT INTO orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at, "
                        + "amount, payment_ref) VALUES (?, ?, ?, ?, clock_timestamp() + ?::interval, ?, ?) RETURNING id",
                Long.class, buyer, SALE, status.name(), "key-" + buyer, expiresIn, AMOUNT,
                hasRef ? "pay_sweep_" + buyer : null);
    }

    String status(long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    long cancelled(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = 'OrderCancelled'",
                Long.class, id);
    }

    long outboxAll(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, id);
    }

    /** The sweep is global, so a due live order of anyone else would be expired by these tests. Refuse to run. */
    void assertNoForeignDueOrders(long fromBuyer, long toBuyer) {
        Long foreign = jdbc.queryForObject("SELECT count(*) FROM orders WHERE status IN ('CREATED', 'PENDING_PAYMENT') "
                + "AND expires_at < clock_timestamp() + interval '10 minutes' AND buyer_id NOT BETWEEN ? AND ?",
                Long.class, fromBuyer, toBuyer);
        assertEquals(0L, foreign, "the dev database holds live orders (due now or within 10 minutes) that this "
                + "test did not write; the sweep would expire them. Reset it (scripts/seed) before running.");
    }

    /** Waits until some backend is blocked on a lock: proof the loser really reached its CAS and is waiting. */
    void awaitLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the second transaction never blocked on the row lock");
    }
}
