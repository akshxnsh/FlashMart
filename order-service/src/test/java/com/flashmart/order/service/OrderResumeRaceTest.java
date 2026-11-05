package com.flashmart.order.service;

import com.flashmart.order.TestPayments;
import com.flashmart.order.TestRows;
import com.flashmart.order.client.payment.PaymentGatewayClient;
import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveRequest;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.dto.CreateOrderRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Race (iii): two retries of the same key resume the reserve loop on one
 * {@code CREATED} order at the same time, on real Postgres, with real threads.
 * The order is inserted first as if the original request had crashed before
 * its loop, so both threads take the resume path.
 *
 * <p>Inventory is mocked at {@link InventoryCalls}: this exercises Order's
 * write-once pricing, the amount CAS and the outbox-only-on-a-winning-CAS
 * rule, not a real Inventory. In the failure race a {@link CyclicBarrier}
 * holds each thread at the entry of {@link OrderCreationTransaction#fail},
 * whose first statement is the {@code markReservationFailed} CAS, until both
 * have arrived, so both really attempt the CAS; without it one thread could
 * finish before the other reaches the CAS and the race would not be exercised.
 * (The barrier sits on {@code fail} rather than on the repository because
 * Mockito cannot call through a spy on the Spring Data interface proxy.)
 *
 * <p>NOT {@code @Transactional}. Buyers 7100..7999 belong to this class; each
 * round's rows are deleted in a {@code finally}.
 */
@SpringBootTest
class OrderResumeRaceTest {

    private static final int ROUNDS = 50;
    private static final long FROM = 7100, TO = 7999;
    private static final long SALE = 7L;
    private static final List<OrderLine> LINES = List.of(new OrderLine(11L, 1), new OrderLine(12L, 2));
    private static final CreateOrderRequest REQUEST = new CreateOrderRequest(SALE, List.of(
            new CreateOrderRequest.Item(11L, 1), new CreateOrderRequest.Item(12L, 2)));

    @Autowired
    private OrderCreationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryCalls inventory;

    /** Real retry and breaker in front; the mock returns "pay_" + key. */
    @MockitoBean
    private PaymentGatewayClient gateway;

    @MockitoSpyBean
    private OrderCreationTransaction transaction;

    /** Set per round by the failure race; null means no barrier. */
    private final AtomicReference<CyclicBarrier> beforeFailCas = new AtomicReference<>();
    private final AtomicInteger pricingWins = new AtomicInteger();
    private final AtomicInteger amountWins = new AtomicInteger();
    private final AtomicInteger pendingWins = new AtomicInteger();

    private long nextBuyer = FROM;

    @BeforeEach
    void instrument() {
        TestPayments.acceptEverything(gateway);
        doAnswer(inv -> {
            int rows = (int) inv.callRealMethod();
            pendingWins.addAndGet(rows);
            return rows;
        }).when(transaction).markPendingPayment(anyLong(), anyString(), anyLong());
        doAnswer(inv -> {
            CyclicBarrier barrier = beforeFailCas.get();
            if (barrier != null) {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return inv.callRealMethod();
        }).when(transaction).fail(anyLong(), anyLong(), anyString());
        doAnswer(inv -> {
            int rows = (int) inv.callRealMethod();
            pricingWins.addAndGet(rows);
            return rows;
        }).when(transaction).priceItem(anyLong(), anyLong(), anyString(), any());
        doAnswer(inv -> {
            int rows = (int) inv.callRealMethod();
            amountWins.addAndGet(rows);
            return rows;
        }).when(transaction).setAmountFromItems(anyLong());
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    record Outcome(CreateOrderResult result, Throwable error) {
    }

    private List<Outcome> race(Callable<CreateOrderResult> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return new Outcome(call.call(), null);
                    } catch (Throwable t) {
                        return new Outcome(null, t);
                    }
                }));
            }
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private long crashedOrder(long buyer) {
        return transaction.insert(buyer, SALE, "k1", LINES);
    }

    private long outboxRows(long id) {
        return jdbc.queryForObject("select count(*) from outbox where aggregate_id = ?", Long.class, id);
    }

    private static void assertNoBrokenRounds(String race, List<String> broken) {
        assertTrue(broken.isEmpty(), race + ": " + broken.size() + " of " + ROUNDS + " rounds broke:\n"
                + String.join("\n", broken));
    }

    /**
     * Both loops get RESERVED for both items. Each item must be priced by
     * exactly one loop (the other's write-once UPDATE matches 0 rows), the
     * amount set by exactly one CAS, and no event written. Both then initiate
     * with the same key and race on the PENDING_PAYMENT CAS: exactly one wins
     * (201), the loser answers 200 with the same paymentRef, and the order ends
     * PENDING_PAYMENT holding that ref. This is also the crash-resume case: the
     * order was CREATED with nothing priced when both retries arrived.
     */
    @Test
    void twoResumedLoopsAllReservedPriceEachItemOnceAndSetTheAmountOnce() throws Exception {
        doAnswer(inv -> new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product"))
                .when(inventory).reserve(any());
        List<String> broken = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            long buyer = nextBuyer++;
            pricingWins.set(0);
            amountWins.set(0);
            pendingWins.set(0);
            try {
                long id = crashedOrder(buyer);
                List<Outcome> outcomes = race(() -> service.create(buyer, "k1", REQUEST));

                List<String> problems = new ArrayList<>();
                outcomes.stream().filter(o -> o.error() != null).forEach(o -> problems.add("error: " + o.error()));
                if (pricingWins.get() != LINES.size()) {
                    problems.add(pricingWins.get() + " pricing writes won (want " + LINES.size() + ")");
                }
                if (amountWins.get() != 1) {
                    problems.add(amountWins.get() + " amount CAS wins (want 1)");
                }
                if (pendingWins.get() != 1) {
                    problems.add(pendingWins.get() + " PENDING_PAYMENT CAS wins (want 1)");
                }
                String ref = "pay_order-" + id;
                Map<String, Object> row = jdbc.queryForMap(
                        "select status, amount, payment_ref from orders where id = ?", id);
                if (!"PENDING_PAYMENT".equals(row.get("status"))
                        || ((BigDecimal) row.get("amount")).compareTo(new BigDecimal("79.00")) != 0
                        || !ref.equals(row.get("payment_ref"))) {
                    problems.add("order " + row + " (want PENDING_PAYMENT, 79.00, " + ref + ")");
                }
                List<Outcome> answered = outcomes.stream().filter(o -> o.result() != null).toList();
                if (answered.stream().filter(o -> o.result().created()).count() != 1) {
                    problems.add("want exactly one 201 (the CAS winner)");
                }
                answered.stream().filter(o -> !ref.equals(o.result().body().paymentRef()))
                        .forEach(o -> problems.add("an answer carries paymentRef " + o.result().body().paymentRef()));
                if (outboxRows(id) != 0) {
                    problems.add(outboxRows(id) + " outbox rows (want 0)");
                }
                if (!problems.isEmpty()) {
                    broken.add("round " + round + ": " + String.join("; ", problems));
                }
            } finally {
                TestRows.deleteOrdersOfBuyers(jdbc, buyer, buyer);
            }
        }
        assertNoBrokenRounds("allReserved", broken);
    }

    /**
     * Both loops get OUT_OF_STOCK on item 12 and both reach the
     * {@code markReservationFailed} CAS together (barrier). One CAS wins; the
     * other blocks on the row lock, re-checks {@code status = 'CREATED'} after
     * the winner commits, and matches 0 rows. Exactly one outbox row may exist.
     */
    @Test
    void twoResumedLoopsBothFailingWriteExactlyOneOrderCancelled() throws Exception {
        doAnswer(inv -> inv.<ReserveRequest>getArgument(0).productId() == 12L
                ? new ReserveResponse(ReserveOutcome.OUT_OF_STOCK, null, null, null, null)
                : new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product"))
                .when(inventory).reserve(any());
        List<String> broken = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            long buyer = nextBuyer++;
            beforeFailCas.set(new CyclicBarrier(2));
            try {
                long id = crashedOrder(buyer);
                List<Outcome> outcomes = race(() -> service.create(buyer, "k1", REQUEST));

                List<String> problems = new ArrayList<>();
                // Both threads answer with an OrderRejectedException (the winner
                // with its reason, the loser replaying with none); anything else
                // (including a broken barrier) is a problem.
                outcomes.stream().filter(o -> !(o.error() instanceof OrderRejectedException))
                        .forEach(o -> problems.add("unexpected outcome: " + o));
                long withReason = outcomes.stream().filter(o -> o.error() instanceof OrderRejectedException r
                        && r.getBody().reason() != null).count();
                if (withReason != 1) {
                    problems.add(withReason + " answers carried a reason (want 1: only the CAS winner)");
                }
                String status = jdbc.queryForObject("select status from orders where id = ?", String.class, id);
                if (!"RESERVATION_FAILED".equals(status)) {
                    problems.add("status " + status);
                }
                if (outboxRows(id) != 1) {
                    problems.add(outboxRows(id) + " outbox rows (want 1)");
                }
                if (!problems.isEmpty()) {
                    broken.add("round " + round + ": " + String.join("; ", problems));
                }
            } finally {
                beforeFailCas.set(null);
                TestRows.deleteOrdersOfBuyers(jdbc, buyer, buyer);
            }
        }
        assertNoBrokenRounds("bothFailing", broken);
    }
}
