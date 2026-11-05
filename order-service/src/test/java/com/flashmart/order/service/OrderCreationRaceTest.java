package com.flashmart.order.service;

import com.flashmart.order.TestRows;
import com.flashmart.order.client.inventory.InventoryClient;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;

/**
 * Two real threads creating orders at once, on real Postgres, {@value #ROUNDS}
 * rounds per race, a fresh buyer per round. Both threads are parked on a latch
 * and released together, so the inserts overlap as much as a JVM allows; that
 * does not control the order inside Postgres. Results are asserted on the
 * database, not only on return values. Every round's problems are collected,
 * so a failure reports how many rounds broke.
 *
 * <p>Inventory is a mock at the Feign interface: these races exercise Order's
 * unique indexes and conflict handling, not a real Inventory.
 *
 * <p>NOT {@code @Transactional}. Buyers 6000..6999 belong to this class; each
 * round's rows are deleted in a {@code finally}.
 */
@SpringBootTest
class OrderCreationRaceTest {

    private static final int ROUNDS = 50;
    private static final long FROM = 6000, TO = 6999;
    private static final long SALE = 7L;

    @Autowired
    private OrderCreationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryClient inventory;

    private long nextBuyer = FROM;

    @BeforeEach
    void inventoryReservesEverything() {
        given(inventory.reserve(any())).willAnswer(inv -> new ReserveResponse(
                ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product"));
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    record Outcome(CreateOrderResult result, Throwable error) {
    }

    private List<Outcome> race(Callable<CreateOrderResult> a, Callable<CreateOrderResult> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<CreateOrderResult> call : List.of(a, b)) {
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

    private static CreateOrderRequest request(long productId) {
        return new CreateOrderRequest(SALE, List.of(new CreateOrderRequest.Item(productId, 1),
                new CreateOrderRequest.Item(productId + 1, 2)));
    }

    private long orderRows(long buyer) {
        return jdbc.queryForObject("select count(*) from orders where buyer_id = ?", Long.class, buyer);
    }

    private static void assertNoBrokenRounds(String race, List<String> broken) {
        assertTrue(broken.isEmpty(), race + ": " + broken.size() + " of " + ROUNDS + " rounds broke:\n"
                + String.join("\n", broken));
    }

    /**
     * (i) Same buyer, sale and key, twice at once. The loser's insert waits on
     * the winner's uncommitted key, then hits the unique violation, re-queries
     * in a new transaction, and replays the winner's order.
     *
     * <p>Asserts: exactly one order row; both answers carry the same order id;
     * one is a new order (201) and one a replay (200); neither is an error.
     * After the reserve loop exists, the loser finds the order CREATED and
     * before its deadline and resumes the loop alongside the winner, so the
     * final state is checked as well: CREATED, every item priced once, amount
     * = SUM(subtotal), no outbox row. How many times Inventory was called is
     * NOT asserted (up to twice per item is allowed; Inventory's replay makes
     * the second harmless, and Inventory here is a mock).
     */
    @Test
    void sameKeyTwiceAtOnceGivesOneOrderAndTheLoserReplaysIt() throws Exception {
        List<String> broken = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            long buyer = nextBuyer++;
            clearInvocations(inventory);
            try {
                CreateOrderRequest request = request(11);
                List<Outcome> outcomes = race(() -> service.create(buyer, "same-key", request),
                        () -> service.create(buyer, "same-key", request));

                List<String> problems = new ArrayList<>();
                outcomes.stream().filter(o -> o.error() != null)
                        .forEach(o -> problems.add("error: " + o.error()));
                if (problems.isEmpty()) {
                    CreateOrderResult x = outcomes.get(0).result(), y = outcomes.get(1).result();
                    if (!x.body().orderId().equals(y.body().orderId())) {
                        problems.add("different order ids " + x.body().orderId() + " / " + y.body().orderId());
                    }
                    if (x.created() == y.created()) {
                        problems.add("want one 201 and one 200, got created=" + x.created() + "/" + y.created());
                    }
                }
                if (orderRows(buyer) != 1) {
                    problems.add(orderRows(buyer) + " order rows (want 1)");
                }
                problems.addAll(finalStateProblems(buyer, outcomes));
                if (!problems.isEmpty()) {
                    broken.add("round " + round + ": " + String.join("; ", problems));
                }
            } finally {
                TestRows.deleteOrdersOfBuyers(jdbc, buyer, buyer);
            }
        }
        assertNoBrokenRounds("sameKeyTwiceAtOnce", broken);
    }

    /**
     * With the reserve loop (Inventory mocked as RESERVED, 10.00 per unit): the
     * order is CREATED, every item priced exactly once with consistent values,
     * amount = SUM(subtotal) + 49.00 fee = 79.00 (1 x 10.00 + 2 x 10.00), and no outbox row.
     * Each response's amount is null or that sum.
     */
    private List<String> finalStateProblems(long buyer, List<Outcome> outcomes) {
        List<String> problems = new ArrayList<>();
        java.util.Map<String, Object> order = jdbc.queryForMap(
                "select id, status, amount from orders where buyer_id = ?", buyer);
        if (!"CREATED".equals(order.get("status"))) {
            problems.add("status " + order.get("status"));
        }
        long unpriced = jdbc.queryForObject("select count(*) from order_items where order_id = ? "
                + "and (unit_price is null or subtotal <> unit_price * quantity)", Long.class, order.get("id"));
        if (unpriced != 0) {
            problems.add(unpriced + " items unpriced or inconsistent");
        }
        BigDecimal amount = (BigDecimal) order.get("amount");
        if (amount == null || amount.compareTo(new BigDecimal("79.00")) != 0) {
            problems.add("amount " + amount + " (want 79.00)");
        }
        long outbox = jdbc.queryForObject("select count(*) from outbox where aggregate_id = ?",
                Long.class, order.get("id"));
        if (outbox != 0) {
            problems.add(outbox + " outbox rows (want 0)");
        }
        for (Outcome o : outcomes) {
            if (o.result() != null && o.result().body().amount() != null
                    && o.result().body().amount().compareTo(new BigDecimal("79.00")) != 0) {
                problems.add("a response says amount " + o.result().body().amount());
            }
        }
        return problems;
    }

    /**
     * (ii) Same buyer and sale, two different keys at once (two tabs). The
     * loser's insert waits on the winner's uncommitted row in
     * {@code uk_orders_buyer_sale_live_or_paid}, then violates it; its key finds
     * nothing, so rule (b) answers 409 ORDER_ALREADY_OPEN with the winner's id.
     */
    @Test
    void twoKeysForOneSaleAtOnceGiveOneOrderAndTheOtherIsAlreadyOpen() throws Exception {
        List<String> broken = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            long buyer = nextBuyer++;
            try {
                List<Outcome> outcomes = race(() -> service.create(buyer, "tab-1", request(11)),
                        () -> service.create(buyer, "tab-2", request(11)));

                List<String> problems = new ArrayList<>();
                List<Outcome> won = outcomes.stream().filter(o -> o.result() != null).toList();
                List<Outcome> lost = outcomes.stream().filter(o -> o.error() != null).toList();
                if (won.size() != 1 || lost.size() != 1) {
                    problems.add(won.size() + " succeeded, " + lost.size() + " failed (want 1 and 1)");
                } else if (!(lost.get(0).error() instanceof OrderRejectedException rejected)
                        || rejected.getBody().code() != ErrorCode.ORDER_ALREADY_OPEN) {
                    problems.add("loser got " + lost.get(0).error());
                } else if (!rejected.getBody().orderId().equals(won.get(0).result().body().orderId())) {
                    problems.add("409 names order " + rejected.getBody().orderId() + ", winner is "
                            + won.get(0).result().body().orderId());
                }
                if (orderRows(buyer) != 1) {
                    problems.add(orderRows(buyer) + " order rows (want 1)");
                }
                if (!problems.isEmpty()) {
                    broken.add("round " + round + ": " + String.join("; ", problems));
                }
            } finally {
                TestRows.deleteOrdersOfBuyers(jdbc, buyer, buyer);
            }
        }
        assertNoBrokenRounds("twoKeysForOneSale", broken);
    }
}
