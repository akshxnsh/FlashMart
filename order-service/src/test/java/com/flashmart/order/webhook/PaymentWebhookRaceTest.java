package com.flashmart.order.webhook;

import com.flashmart.order.TestRows;
import com.flashmart.order.entity.OrderStatus;
import com.flashmart.order.sweep.ExpirySweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.flashmart.order.webhook.WebhookRows.AMOUNT;
import static com.flashmart.order.webhook.WebhookRows.refOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook's {@code markPaid} racing the expiry sweep's {@code expire} on
 * one {@code PENDING_PAYMENT} order, with real threads on real Postgres. Both
 * are CAS on the same row, so Postgres serialises them on the row lock and
 * exactly one can win; under READ COMMITTED the blocked one re-checks its
 * {@code WHERE} against the winner's committed row and matches 0 rows.
 *
 * <p>The sweep side is the REAL pass, {@link ExpirySweep#runOnce()} (Day 7,
 * point 6): its SELECT, then {@code expire} plus {@code OrderCancelled{ORDER_EXPIRED}}
 * in one transaction, written only if it won. Each order here is made due (its
 * deadline a minute past) so the pass picks it. The sweep is global, so the
 * class refuses to run while the database holds anyone else's due live orders.
 *
 * <p>Two orderings are forced (the winner holds its row lock uncommitted until
 * the loser is observed waiting on it in {@code pg_stat_activity}), then many
 * free-running rounds. The webhook side is called at the service, not over
 * HTTP, except in the commit-before-200 test. What this cannot see: the
 * scheduler, two processes, the real gateway, Kafka.
 *
 * <p>NOT {@code @Transactional}: everything commits. Buyers 10100..10999; rows
 * deleted after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentWebhookRaceTest {

    private static final long FROM = 10100, TO = 10999;
    private static final int ROUNDS = 100;

    @Autowired
    private PaymentWebhookService webhooks;

    @Autowired
    private ExpirySweep sweep;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookSignature signature;

    private WebhookRows rows;
    private long nextBuyer = FROM;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        rows = new WebhookRows(jdbc);
        Long foreign = jdbc.queryForObject("SELECT count(*) FROM orders WHERE status IN ('CREATED', 'PENDING_PAYMENT') "
                + "AND expires_at < clock_timestamp() + interval '10 minutes' AND buyer_id NOT BETWEEN ? AND ?",
                Long.class, FROM, TO);
        assertEquals(0L, foreign, "the dev database holds live orders this test did not write; the real sweep "
                + "pass would expire them. Reset it (scripts/seed) before running this class.");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    /** A PENDING_PAYMENT order whose payment window ended a minute ago, so the sweep picks it. */
    private long dueOrder(long buyer) {
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);
        jdbc.update("UPDATE orders SET expires_at = clock_timestamp() - interval '1 minute' WHERE id = ?", id);
        return id;
    }

    /** One real sweep pass; how many orders it expired (only ever this test's one due order). */
    private int sweepExpire() {
        return sweep.runOnce().expired();
    }

    private PaymentWebhook success(long id, long buyer) {
        return new PaymentWebhook(id, refOf(buyer), PaymentWebhook.Status.SUCCESS, AMOUNT);
    }

    /** Waits until some backend is blocked on a lock: proof the loser really reached its CAS and is waiting. */
    private void awaitLockWait() throws InterruptedException {
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

    private void assertExpireWon(long id) {
        assertEquals("ORDER_EXPIRED", rows.status(id));
        assertEquals(1, rows.outbox(id, "OrderCancelled"));
        assertEquals(0, rows.outbox(id, "PaymentConfirmed"));
        assertEquals(1, rows.refunds(id), "the payment nobody keeps is refunded");
        assertEquals(0, AMOUNT.compareTo(jdbc.queryForObject(
                "SELECT amount FROM refunds WHERE order_id = ?", BigDecimal.class, id)));
    }

    private void assertWebhookWon(long id) {
        assertEquals("PAID", rows.status(id));
        assertEquals(1, rows.outbox(id, "PaymentConfirmed"));
        assertEquals(0, rows.outbox(id, "OrderCancelled"), "never OrderCancelled for a PAID order");
        assertEquals(0, rows.refunds(id));
    }

    /** The sweep commits first while the webhook is blocked in markPaid: the webhook loses and refunds. */
    @Test
    void sweepHoldsTheRowWebhookBlocksThenLosesAndRefunds() throws Exception {
        long buyer = nextBuyer++;
        long id = dueOrder(buyer);
        CountDownLatch sweepUpdated = new CountDownLatch(1);
        CountDownLatch releaseSweep = new CountDownLatch(1);

        // An outer transaction around the pass: the per-order transaction
        // joins it, so the ORDER_EXPIRED row stays locked until released.
        Future<Integer> sweep = pool.submit(() -> tx.execute(s -> {
            int won = sweepExpire();
            sweepUpdated.countDown();
            await(releaseSweep);
            return won;
        }));
        assertTrue(sweepUpdated.await(10, TimeUnit.SECONDS));
        Future<WebhookOutcome> webhook = pool.submit(() -> webhooks.apply(success(id, buyer)));
        awaitLockWait();
        assertFalse(webhook.isDone(), "the webhook must be waiting on the sweep's row lock");
        releaseSweep.countDown();

        assertEquals(1, sweep.get(10, TimeUnit.SECONDS));
        assertEquals(WebhookOutcome.REFUND_RECORDED, webhook.get(10, TimeUnit.SECONDS));
        assertExpireWon(id);
    }

    /** The webhook commits first while the sweep is blocked in expire: the sweep sees 0 rows. */
    @Test
    void webhookHoldsTheRowSweepBlocksThenLosesWithZeroRows() throws Exception {
        long buyer = nextBuyer++;
        long id = dueOrder(buyer);
        CountDownLatch webhookApplied = new CountDownLatch(1);
        CountDownLatch releaseWebhook = new CountDownLatch(1);

        // An outer transaction around apply(): its @Transactional joins it, so
        // the PAID row stays locked and uncommitted until released.
        Future<WebhookOutcome> webhook = pool.submit(() -> tx.execute(s -> {
            WebhookOutcome outcome = webhooks.apply(success(id, buyer));
            webhookApplied.countDown();
            await(releaseWebhook);
            return outcome;
        }));
        assertTrue(webhookApplied.await(10, TimeUnit.SECONDS));
        // The pass's SELECT still sees the committed PENDING_PAYMENT row, picks
        // it, and blocks in expire().
        Future<Integer> sweep = pool.submit(this::sweepExpire);
        awaitLockWait();
        assertFalse(sweep.isDone(), "the sweep must be waiting on the webhook's row lock");
        releaseWebhook.countDown();

        assertEquals(WebhookOutcome.PAID, webhook.get(10, TimeUnit.SECONDS));
        assertEquals(0, sweep.get(10, TimeUnit.SECONDS), "the loser must see 0 rows");
        assertWebhookWon(id);
    }

    /** Both released together, no forced order. Every round: exactly one winner and its side effects only. */
    @Test
    void freeRunningRacesAlwaysHaveExactlyOneWinner() throws Exception {
        int webhookWins = 0, sweepWins = 0;
        List<String> broken = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            long buyer = nextBuyer++;
            long id = dueOrder(buyer);
            CountDownLatch go = new CountDownLatch(1);
            Future<WebhookOutcome> webhook = pool.submit(() -> {
                go.await();
                return webhooks.apply(success(id, buyer));
            });
            Future<Integer> sweep = pool.submit(() -> {
                go.await();
                return sweepExpire();
            });
            go.countDown();
            WebhookOutcome outcome = webhook.get(10, TimeUnit.SECONDS);
            int swept = sweep.get(10, TimeUnit.SECONDS);
            try {
                if (outcome == WebhookOutcome.PAID && swept == 0) {
                    webhookWins++;
                    assertWebhookWon(id);
                } else if (outcome == WebhookOutcome.REFUND_RECORDED && swept == 1) {
                    sweepWins++;
                    assertExpireWon(id);
                } else {
                    broken.add("round " + round + ": webhook " + outcome + ", sweep rows " + swept);
                }
            } catch (AssertionError e) {
                broken.add("round " + round + ": " + e.getMessage());
            }
        }
        System.out.printf("webhook-vs-sweep free race: %d rounds, webhook won %d, sweep won %d%n",
                ROUNDS, webhookWins, sweepWins);
        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    /** Over HTTP, committing for real: once 200 is back, another connection already sees PAID. */
    @Test
    void twoHundredIsSentOnlyAfterTheChangeIsCommitted() throws Exception {
        long buyer = nextBuyer++;
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);
        byte[] body = WebhookRows.body(id, refOf(buyer), "SUCCESS", "499.00").getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/webhooks/payment").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(PaymentWebhookController.SIGNATURE_HEADER, HexFormat.of().formatHex(signature.sign(body))))
                .andExpect(status().isOk());

        assertEquals("PAID", rows.status(id));
        assertEquals(1, rows.outbox(id, "PaymentConfirmed"));
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
