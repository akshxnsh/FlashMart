package com.flashmart.order.sweep;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The expiry sweep (decisions.md, "Day 7", point 6). One pass: pick up to
 * {@code batch-size} due live orders, then expire each in its OWN short
 * transaction ({@link ExpiryTransaction}). Leftovers wait for the next pass.
 *
 * <p>Deliberately NOT transactional and NOT {@code FOR UPDATE SKIP LOCKED}:
 * the SELECT only proposes candidates; the {@code expire} CAS decides. Two
 * instances may pick the same order; one wins the CAS, the other gets 0 rows
 * and writes nothing. A payment start or a webhook racing the sweep on one
 * row is decided the same way: one row, one winner.
 *
 * <p>The statuses are LITERALS in the SQL, not a parameter: Postgres can use
 * the partial index {@code idx_orders_expiry_sweep_live} (V9) only if it can
 * see at plan time that this WHERE implies the index predicate.
 * {@code clock_timestamp()}, not {@code now()}, as everywhere {@code expires_at}
 * is compared.
 *
 * <p>One order failing (any exception) is logged and skipped; the rest of the
 * batch still runs. It stays due, so the next pass retries it.
 */
@Slf4j
@Component
public class ExpirySweep {

    static final String DUE_ORDERS = "SELECT id, buyer_id, status FROM orders "
            + "WHERE status IN ('CREATED', 'PENDING_PAYMENT') AND expires_at < clock_timestamp() "
            + "ORDER BY expires_at LIMIT ?";

    private final JdbcTemplate jdbc;
    private final ExpiryTransaction transaction;
    private final int batchSize;

    public ExpirySweep(JdbcTemplate jdbc, ExpiryTransaction transaction,
                       @Value("${order.sweep.batch-size}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("order.sweep.batch-size must be at least 1, was " + batchSize);
        }
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.batchSize = batchSize;
    }

    record Due(long id, long buyerId, String status) {}

    /** What one pass did: candidates read, orders this pass expired, orders that threw. */
    public record Result(int picked, int expired, int failed) {}

    public Result runOnce() {
        List<Due> due = jdbc.query(DUE_ORDERS,
                (rs, n) -> new Due(rs.getLong("id"), rs.getLong("buyer_id"), rs.getString("status")), batchSize);
        int expired = 0, failed = 0;
        for (Due order : due) {
            try {
                if (transaction.expire(order.id(), order.buyerId(), order.status())) {
                    expired++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("expiry sweep could not expire order {} ({}); it stays due and is retried next pass",
                        order.id(), order.status(), e);
            }
        }
        return new Result(due.size(), expired, failed);
    }
}
