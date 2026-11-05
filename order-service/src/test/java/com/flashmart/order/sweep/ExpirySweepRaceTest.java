package com.flashmart.order.sweep;

import com.flashmart.order.TestRows;
import com.flashmart.order.entity.OrderStatus;
import com.flashmart.order.service.OrderCreationTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real sweep pass racing (a) the payment step's {@code markPendingPayment}
 * on one due {@code CREATED} order (Riya pays just as her order times out),
 * and (b) another sweep pass, with real threads on real Postgres. Every side is
 * a CAS on the same row, so Postgres serialises them on the row lock; under
 * READ COMMITTED the blocked one re-checks its WHERE against the winner's
 * committed row and matches 0 rows.
 *
 * <p>Forced orderings hold the winner's row lock uncommitted (an outer
 * transaction that the inner {@code @Transactional} methods join) until the
 * loser is seen waiting in {@code pg_stat_activity}; then free-running rounds.
 *
 * <p>What this cannot see: two JVMs or two database connections pools (both
 * "instances" here share one pool of 4), the scheduler, the HTTP answer the
 * losing payment request gives (409 ORDER_EXPIRED, tested in the payment-step
 * tests), the gateway, and Kafka. Contention test, not a load test.
 *
 * <p>NOT {@code @Transactional}. Buyers 12000..12499 belong to this class.
 */
@SpringBootTest
class ExpirySweepRaceTest {

    private static final long FROM = 12000, TO = 12499;
    private static final int ROUNDS = 100;
    private static final long WINDOW_MS = 360_000;

    @Autowired
    private ExpirySweep sweep;

    @Autowired
    private OrderCreationTransaction payment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    private SweepRows rows;
    private long nextBuyer = FROM;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        rows = new SweepRows(jdbc);
        rows.assertNoForeignDueOrders(FROM, TO);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    private long dueCreated() {
        return rows.order(nextBuyer++, OrderStatus.CREATED, "-1 minute");
    }

    private String ref(long id) {
        return "pay_race_" + id;
    }

    private void assertSweepWon(long id) {
        assertEquals("ORDER_EXPIRED", rows.status(id));
        assertEquals(1, rows.cancelled(id));
        assertEquals(1, rows.outboxAll(id));
    }

    private void assertPaymentWon(long id) {
        assertEquals("PENDING_PAYMENT", rows.status(id));
        assertEquals(ref(id), jdbc.queryForObject("SELECT payment_ref FROM orders WHERE id = ?", String.class, id));
        assertEquals(0, rows.outboxAll(id), "a payment starting writes no event, and the sweep lost");
    }

    // ---- sweep vs payment start ----

    @Test
    void sweepHoldsTheRowPaymentBlocksThenGetsZero() throws Exception {
        long id = dueCreated();
        CountDownLatch swept = new CountDownLatch(1), release = new CountDownLatch(1);

        Future<ExpirySweep.Result> sweeping = pool.submit(() -> tx.execute(s -> {
            ExpirySweep.Result r = sweep.runOnce();
            swept.countDown();
            await(release);
            return r;
        }));
        assertTrue(swept.await(10, TimeUnit.SECONDS));
        Future<Integer> paying = pool.submit(() -> payment.markPendingPayment(id, ref(id), WINDOW_MS));
        rows.awaitLockWait();
        assertFalse(paying.isDone(), "the payment CAS must be waiting on the sweep's row lock");
        release.countDown();

        assertEquals(new ExpirySweep.Result(1, 1, 0), sweeping.get(10, TimeUnit.SECONDS));
        assertEquals(0, paying.get(10, TimeUnit.SECONDS), "the payment CAS loses with 0 rows");
        assertSweepWon(id);
    }

    @Test
    void paymentHoldsTheRowSweepBlocksThenGetsZero() throws Exception {
        long id = dueCreated();
        CountDownLatch paid = new CountDownLatch(1), release = new CountDownLatch(1);

        Future<Integer> paying = pool.submit(() -> tx.execute(s -> {
            int won = payment.markPendingPayment(id, ref(id), WINDOW_MS);
            paid.countDown();
            await(release);
            return won;
        }));
        assertTrue(paid.await(10, TimeUnit.SECONDS));
        // The sweep's SELECT still sees the old committed row (CREATED, due),
        // so it picks the order and blocks in expire().
        Future<ExpirySweep.Result> sweeping = pool.submit(() -> sweep.runOnce());
        rows.awaitLockWait();
        assertFalse(sweeping.isDone(), "the sweep must be waiting on the payment's row lock");
        release.countDown();

        assertEquals(1, paying.get(10, TimeUnit.SECONDS));
        assertEquals(new ExpirySweep.Result(1, 0, 0), sweeping.get(10, TimeUnit.SECONDS),
                "picked, but the CAS matched 0 rows and wrote nothing");
        assertPaymentWon(id);
    }

    @Test
    void sweepVsPaymentFreeRunningAlwaysHasExactlyOneWinner() throws Exception {
        int paymentWins = 0, sweepWins = 0;
        List<String> broken = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            long id = dueCreated();
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> paying = pool.submit(() -> {
                go.await();
                return payment.markPendingPayment(id, ref(id), WINDOW_MS);
            });
            Future<ExpirySweep.Result> sweeping = pool.submit(() -> {
                go.await();
                return sweep.runOnce();
            });
            go.countDown();
            int won = paying.get(10, TimeUnit.SECONDS);
            int expired = sweeping.get(10, TimeUnit.SECONDS).expired();
            try {
                if (won == 1 && expired == 0) {
                    paymentWins++;
                    assertPaymentWon(id);
                } else if (won == 0 && expired == 1) {
                    sweepWins++;
                    assertSweepWon(id);
                } else {
                    broken.add("round " + round + ": payment rows " + won + ", sweep expired " + expired);
                }
            } catch (AssertionError e) {
                broken.add("round " + round + ": " + e.getMessage());
            }
        }
        System.out.printf("sweep-vs-payment free race: %d rounds, payment won %d, sweep won %d%n",
                ROUNDS, paymentWins, sweepWins);
        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    // ---- two sweep passes at once (two instances) ----

    // 20 rounds x 10 due orders, two passes released together. Whatever each
    // pass picked, every order ends ORDER_EXPIRED with ONE OrderCancelled, and
    // the passes' expired counts add up to 10.
    @Test
    void twoPassesAtOnceWriteOneOrderCancelledPerOrder() throws Exception {
        List<String> broken = new ArrayList<>();
        int overlapping = 0;
        for (int round = 1; round <= 20; round++) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ids.add(dueCreated());
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ExpirySweep.Result>> passes = new ArrayList<>();
            for (int p = 0; p < 2; p++) {
                passes.add(pool.submit(() -> {
                    go.await();
                    return sweep.runOnce();
                }));
            }
            go.countDown();
            ExpirySweep.Result a = passes.get(0).get(30, TimeUnit.SECONDS);
            ExpirySweep.Result b = passes.get(1).get(30, TimeUnit.SECONDS);
            if (a.picked() + b.picked() > 10) {
                overlapping++; // both passes picked at least one same order
            }
            if (a.expired() + b.expired() != 10 || a.failed() + b.failed() != 0) {
                broken.add("round " + round + ": passes " + a + " and " + b);
            }
            for (long id : ids) {
                if (!"ORDER_EXPIRED".equals(rows.status(id)) || rows.cancelled(id) != 1) {
                    broken.add("round " + round + ": order " + id + " is " + rows.status(id) + " with "
                            + rows.cancelled(id) + " OrderCancelled rows");
                }
            }
        }
        System.out.printf("two-pass race: 20 rounds, %d rounds where both passes picked the same order%n",
                overlapping);
        assertTrue(broken.isEmpty(), String.join("\n", broken));
        assertTrue(overlapping > 0, "the passes never picked the same order, so the CAS was never contested");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
