package com.flashmart.order.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Moves unpublished {@code outbox} rows to Kafka (decisions.md, "Day 6", point 6).
 * One call to {@link #runOnce()} is one short database transaction:
 *
 * <ol>
 *   <li>Claim up to {@code batch-size} rows, oldest first, {@code FOR UPDATE
 *       SKIP LOCKED}: a second relay instance skips rows this one holds rather
 *       than waiting for them, and takes the next ones.</li>
 *   <li>For each row, in order: send, wait for the broker's ack (bounded by
 *       the ack timeout), and only THEN set {@code published_at} on the database
 *       clock. Marking before the ack would turn a failed send into a lost event.</li>
 *   <li>On the first failure: {@code attempts + 1}, {@code last_error}, stop.
 *       The rows already sent in this batch keep their {@code published_at};
 *       the failed row and every row after it stay unpublished and are retried
 *       next pass. Stopping bounds how long this transaction holds its row
 *       locks to about one ack timeout after a failure.</li>
 * </ol>
 *
 * <p>AT-LEAST-ONCE, never exactly-once: if the process dies (or the commit
 * fails) after an ack and before the commit, the row is still unpublished and is
 * sent again. A duplicate is expected; a loss is a bug. Ordering across rows is
 * not guaranteed either (a failed row is retried after rows another instance
 * already sent).
 *
 * <p>Value = {@code payload_json} as stored (Postgres {@code jsonb} text: the
 * same flat object, with jsonb's own key order and spacing). Key = the order id.
 */
@Slf4j
@Component
public class OutboxRelay {

    private static final int MAX_ERROR_LENGTH = 1000;

    record Row(long id, long aggregateId, String eventType, String payload) {
    }

    /** What one pass did. {@code failedRowId} is null when nothing failed. */
    public record Result(int claimed, int published, Long failedRowId) {
    }

    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getLong("id"), rs.getLong("aggregate_id"),
            rs.getString("event_type"), rs.getString("payload"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final RelaySettings settings;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka,
                       RelaySettings settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.settings = settings;
    }

    public Result runOnce() {
        return tx.execute(status -> {
            List<Row> rows = jdbc.query("SELECT id, aggregate_id, event_type, payload_json::text AS payload "
                            + "FROM outbox WHERE published_at IS NULL ORDER BY created_at, id LIMIT ? "
                            + "FOR UPDATE SKIP LOCKED", ROW, settings.batchSize());
            int published = 0;
            for (Row row : rows) {
                try {
                    publish(row);
                    markPublished(row);
                    published++;
                } catch (Exception e) {
                    recordFailure(row, e);
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return new Result(rows.size(), published, row.id());
                }
            }
            return new Result(rows.size(), published, null);
        });
    }

    /** Send and wait for the ack; send + ack together never exceed the ack timeout. */
    private void publish(Row row) throws InterruptedException, ExecutionException, TimeoutException {
        String topic = OutboxTopics.topicFor(row.eventType()).orElseThrow(() -> new IllegalStateException(
                "unknown event_type '" + row.eventType() + "': no topic (V5 CHECK and OutboxTopics disagree)"));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.ackTimeoutMs());
        var ack = kafka.send(topic, Long.toString(row.aggregateId()), row.payload());
        try {
            ack.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // Future.get's TimeoutException has no message; last_error would read "null".
            TimeoutException named = new TimeoutException("no broker ack within " + settings.ackTimeoutMs() + " ms");
            named.initCause(e);
            throw named;
        }
    }

    /** Only after the ack. We hold the row lock, so anything but 1 row means something is badly wrong. */
    private void markPublished(Row row) {
        int marked = jdbc.update("UPDATE outbox SET published_at = clock_timestamp() "
                + "WHERE id = ? AND published_at IS NULL", row.id());
        if (marked != 1) {
            throw new IllegalStateException("outbox row " + row.id() + " was not ours to mark (" + marked + " rows)");
        }
    }

    /**
     * Runs in the same transaction, which commits with it. If the failure was a
     * database error the transaction is already aborted, this update throws
     * too, and the whole pass rolls back: every row of the batch stays
     * unpublished and is re-sent (duplicates, not losses).
     */
    private void recordFailure(Row row, Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        // The top exception is often generic ("Send failed"); the root says why.
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage()
                + (root == cause ? "" : " | root: " + root.getClass().getSimpleName() + ": " + root.getMessage());
        if (error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
        log.warn("outbox row {} ({} for order {}) not published, batch stopped: {}",
                row.id(), row.eventType(), row.aggregateId(), error);
        jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?", error, row.id());
    }
}
