package com.flashmart.inventory.warmup;

import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.service.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the real reserve code path a few times BEFORE the web server opens its
 * port (decisions.md, "Day 9: cold-start warm-up"). Order reaches Inventory by
 * a fixed URL, so the first buyers after a restart must not be the ones who
 * pay for a cold JVM (the diagnosis: a fresh JVM + 200 concurrent reserves left
 * 87 to 150 calls over Order's 2 s limit; after 20 sequential calls, none).
 *
 * <p><b>Why this hook.</b> {@link SmartInitializingSingleton} runs once every
 * bean exists (Flyway has migrated, JPA and the DataSource are up), and before
 * the context's lifecycle phase that opens the Tomcat connector, registers with
 * Eureka and starts the Kafka listeners. An {@code ApplicationRunner} runs
 * after all three, which is too late.
 *
 * <p><b>No lasting effect.</b> Each call is {@link ReservationService#reserve}
 * inside an outer transaction that is ALWAYS rolled back, so the reservation
 * insert and the stock decrement never commit. The one thing that does not roll
 * back is the {@code stock_reservations} id sequence: every start burns
 * {@code calls} ids (a gap in the ids, nothing else). The rows are keyed on a
 * synthetic order id near {@code Long.MAX_VALUE}, which no real order reaches.
 *
 * <p><b>Bounded and non-fatal.</b> The calls run on one daemon thread; the
 * caller waits at most {@code timeout-ms}, and the transactions carry a
 * matching transaction timeout so a stuck statement cannot hold the stock row
 * lock for long. Any failure, timeout or an empty database is logged and start-up
 * goes on. This class never throws out of {@link #afterSingletonsInstantiated()}.
 *
 * <p><b>What it cannot warm.</b> Everything that needs a request on the port:
 * Tomcat's threads, the DispatcherServlet's first-request set-up, the request
 * body's Jackson deserialisation and the response's serialisation, bean
 * validation of the request. It warms the service layer, transactions, Spring
 * Data and Hibernate paths, the JDBC driver and the connections.
 */
@Slf4j
@Component
public class StartupWarmup implements SmartInitializingSingleton {

    /** Close to Long.MAX_VALUE so it can never be a real order id; see the class comment. */
    static final long SYNTHETIC_ORDER_BASE = Long.MAX_VALUE;

    private final boolean enabled;
    private final int calls;
    private final long timeoutMs;
    private final ReservationService reservations;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public StartupWarmup(@Value("${startup-warmup.enabled}") boolean enabled,
                         @Value("${startup-warmup.calls}") int calls,
                         @Value("${startup-warmup.timeout-ms}") long timeoutMs,
                         ReservationService reservations,
                         JdbcTemplate jdbc,
                         PlatformTransactionManager transactionManager) {
        if (calls < 1 || timeoutMs < 1000) {
            throw new IllegalStateException("bad startup-warmup config: calls >= 1 and timeout-ms >= 1000");
        }
        this.enabled = enabled;
        this.calls = calls;
        this.timeoutMs = timeoutMs;
        this.reservations = reservations;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        // The database-side bound on each warm-up transaction (whole seconds, rounded up).
        this.tx.setTimeout((int) ((timeoutMs + 999) / 1000));
    }

    @Override
    public void afterSingletonsInstantiated() {
        run();
    }

    /** What happened, for logs and tests. {@code completedCalls} is how many reserve calls returned. */
    public record Result(Status status, int completedCalls, long elapsedMs) {
    }

    public enum Status { DISABLED, SKIPPED_NO_ITEM, DONE, FAILED, TIMED_OUT }

    /** Never throws. */
    public Result run() {
        if (!enabled) {
            log.info("startup warm-up: disabled");
            return new Result(Status.DISABLED, 0, 0);
        }
        long start = System.nanoTime();
        int[] completed = {0};
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "startup-warmup");
            thread.setDaemon(true);
            return thread;
        });
        Future<Status> future = executor.submit(() -> warm(completed));
        Status status;
        try {
            status = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            status = Status.TIMED_OUT;
            log.warn("startup warm-up: timed out after {} ms ({} calls done); starting anyway", timeoutMs, completed[0]);
        } catch (ExecutionException e) {
            status = Status.FAILED;
            log.warn("startup warm-up: failed after {} calls; starting anyway", completed[0], e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = Status.FAILED;
            log.warn("startup warm-up: interrupted; starting anyway");
        } finally {
            executor.shutdown();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (status == Status.DONE) {
            log.info("startup warm-up: {} reserve calls, all rolled back, {} ms", completed[0], elapsedMs);
        }
        return new Result(status, completed[0], elapsedMs);
    }

    private Status warm(int[] completed) {
        long[] target = pickItem();
        if (target == null) {
            log.info("startup warm-up: no flash sale item in the database, skipped");
            return Status.SKIPPED_NO_ITEM;
        }
        for (int i = 0; i < calls; i++) {
            long orderId = SYNTHETIC_ORDER_BASE - i;
            tx.executeWithoutResult(status -> {
                reservations.reserve(new ReserveRequest(orderId, target[0], target[1], 1));
                status.setRollbackOnly(); // always: nothing of a warm-up commits
            });
            completed[0]++;
        }
        return Status.DONE;
    }

    /**
     * A live item with stock first, so the warm-up takes the success path (insert, then
     * decrement). Else any item (the not-live / out-of-stock paths still warm most of it).
     * The liveness text here only chooses WHICH path gets warmed; reserve itself decides
     * liveness, in its own statement, as always.
     */
    private long[] pickItem() {
        List<long[]> live = jdbc.query("""
                SELECT i.flash_sale_id, i.product_id
                FROM flash_sale_items i JOIN flash_sales s ON s.id = i.flash_sale_id
                WHERE s.status <> 'CANCELLED' AND s.start_time <= now() AND now() < s.end_time
                  AND i.available_stock >= 1
                ORDER BY i.id LIMIT 1
                """, (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)});
        if (!live.isEmpty()) {
            return live.get(0);
        }
        List<long[]> any = jdbc.query(
                "SELECT flash_sale_id, product_id FROM flash_sale_items ORDER BY id LIMIT 1",
                (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)});
        return any.isEmpty() ? null : any.get(0);
    }
}
