package com.flashmart.inventory.reconciler;

import com.flashmart.inventory.service.ReservationEventService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link StaleReservationReconciler} on real Postgres with Order mocked
 * ({@link OrderStatusClient} is a mock; its HTTP side is tested in
 * {@code OrderStatusClientTest}). NOT {@code @Transactional}: release and
 * confirm must really commit, and the race needs two connections.
 *
 * <p>The pass reads EVERY stale RESERVED row in the shared database, so this
 * class's rows are made very old (days) so they come first, its order ids
 * start at 890_000_000, and the mock answers "not found" for any order that is
 * not this class's. Assertions are made on this class's rows only.
 */
@SpringBootTest
class StaleReservationReconcilerTest {

    private static final long BRAND_ID = 9102L;
    private static final AtomicLong NEXT_ORDER = new AtomicLong(890_000_000L);

    @Autowired
    private StaleReservationReconciler reconciler;

    @Autowired
    private ReconcilerSettings settings;

    @Autowired
    private ReservationEventService events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @MockitoBean
    private OrderStatusClient order;

    private final List<Long> saleIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();
    private final AtomicLong age = new AtomicLong(100_000);

    @org.junit.jupiter.api.BeforeEach
    void everyOtherOrderIsUnknown() {
        // Rows of other tests or of the dev data are "not found": never acted on.
        given(order.statusOf(anyLong())).willReturn(new OrderStatusClient.Answer.NotFound());
        // The reconciler is one bean for the whole class and keeps its cursor in
        // memory. Run passes until one reaches the end, so every test starts from
        // the oldest row. These passes act on nothing: every order is "not found".
        for (int i = 0; i < 20; i++) {
            StaleReservationReconciler.Result r = reconciler.runOnce();
            if (r.stoppedAt() == null && r.rows() < settings.batchSize()) {
                break;
            }
        }
        org.mockito.Mockito.clearInvocations(order);
    }

    @AfterEach
    void cleanUp() {
        for (Long itemId : itemIds) {
            jdbc.update("DELETE FROM stock_reservations WHERE flash_sale_item_id = ?", itemId);
            jdbc.update("DELETE FROM flash_sale_items WHERE id = ?", itemId);
        }
        saleIds.forEach(id -> jdbc.update("DELETE FROM flash_sales WHERE id = ?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
    }

    @Test
    void theThresholdIsDerivedFromOrdersTimingsPlusADrainMargin() {
        // 360 s payment window + 60.5 s longest CREATED deadline + 30 s sweep + 120 s drain margin.
        assertEquals(570_500L, settings.thresholdMs());
        assertEquals(50, settings.batchSize());
    }

    @Test
    void paidIsConfirmedAndTheStockStaysTaken() {
        long item = item(100), o = order();
        stale(o, item, 2);
        answer(o, "PAID");
        double before = actions("confirmed");

        StaleReservationReconciler.Result r = reconciler.runOnce();

        assertEquals("CONFIRMED", status(o, item));
        assertEquals(98, stock(item));
        assertNull(r.stoppedAt());
        assertEquals(1.0, actions("confirmed") - before);
    }

    @Test
    void eachTerminalFailureReleasesAndGivesTheStockBack() {
        for (String failed : List.of("RESERVATION_FAILED", "PAYMENT_UNAVAILABLE", "ORDER_EXPIRED", "PAYMENT_FAILED")) {
            long item = item(100), other = order(), o = order();
            reserve(other, item, 10, "1 second"); // someone else's live hold on the same item
            stale(o, item, 3);
            answer(o, failed);

            reconciler.runOnce();

            assertEquals("RELEASED", status(o, item), failed);
            assertEquals("RESERVED", status(other, item), failed);
            assertEquals(90, stock(item), failed + ": only this order's 3 units come back");
        }
    }

    @Test
    void liveUnknownAndNotFoundAnswersChangeNothingAndAlertButThePassGoesOn() {
        long item = item(100);
        long created = order(), pending = order(), unknown = order(), missing = order(), paidAfter = order();
        stale(created, item, 1);
        stale(pending, item, 1);
        stale(unknown, item, 1);
        stale(missing, item, 1);
        stale(paidAfter, item, 1);
        answer(created, "CREATED");
        answer(pending, "PENDING_PAYMENT");
        answer(unknown, "REFUNDED_SOMEHOW");
        given(order.statusOf(missing)).willReturn(new OrderStatusClient.Answer.NotFound());
        answer(paidAfter, "PAID");
        double live = alerts("order_live"), notFound = alerts("order_not_found"), odd = alerts("unknown_status");

        StaleReservationReconciler.Result r = reconciler.runOnce();

        for (long o : List.of(created, pending, unknown, missing)) {
            assertEquals("RESERVED", status(o, item), "order " + o);
        }
        assertEquals("CONFIRMED", status(paidAfter, item), "the pass went on past the non-terminal answers");
        assertEquals(95, stock(item), "no stock moved for the four held rows; the paid one stays taken");
        assertNull(r.stoppedAt());
        assertEquals(2.0, alerts("order_live") - live);
        assertEquals(1.0, alerts("order_not_found") - notFound);
        assertEquals(1.0, alerts("unknown_status") - odd);
    }

    @Test
    void anUnreachableOrderStopsThePassAndIsRetriedAfterTheWrap() {
        long item = item(100), first = order(), second = order();
        stale(first, item, 1);   // older, asked first
        stale(second, item, 1);
        given(order.statusOf(first)).willReturn(new OrderStatusClient.Answer.Unreachable("connect timed out"));
        answer(second, "ORDER_EXPIRED");
        double unreachable = alerts("order_unreachable");

        StaleReservationReconciler.Result r = reconciler.runOnce();

        assertEquals(first, r.stoppedAt());
        verify(order, never()).statusOf(second);
        assertEquals("RESERVED", status(first, item));
        assertEquals("RESERVED", status(second, item), "not asked, not touched");
        assertEquals(98, stock(item));
        assertEquals(1.0, alerts("order_unreachable") - unreachable);

        // Order is back. The next pass starts AFTER the failed order (cursor), so it
        // handles `second` and reaches the end; the one after that wraps around.
        answer(first, "PAYMENT_UNAVAILABLE");
        reconciler.runOnce();
        assertEquals("RESERVED", status(first, item), "skipped until the wrap-around");
        assertEquals("RELEASED", status(second, item));
        reconciler.runOnce();
        assertEquals("RELEASED", status(first, item));
        assertEquals(100, stock(item));
    }

    @Test
    void aYoungRowIsNeverAskedAbout() {
        long item = item(100), young = order(), old = order();
        // Just under the threshold: an order that can still be settled by its own events.
        reserve(young, item, 1, (settings.thresholdMs() / 1000 - 30) + " seconds");
        stale(old, item, 1);
        answer(young, "RESERVATION_FAILED");
        answer(old, "RESERVATION_FAILED");

        reconciler.runOnce();

        verify(order, never()).statusOf(young);
        assertEquals("RESERVED", status(young, item));
        assertEquals("RELEASED", status(old, item));
    }

    @Test
    void onePassLooksAtNoMoreThanTheBatchAndAnOrderWithManyRowsIsAskedOnce() {
        long item = item(1000);
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long o = order();
            orders.add(o);
            stale(o, item, 1);
            answer(o, "ORDER_EXPIRED");
        }

        StaleReservationReconciler.Result first = reconciler.runOnce();

        assertEquals(50, first.rows());
        assertEquals(50, released(orders), "the 50 oldest");
        assertEquals("RESERVED", status(orders.get(59), item), "the youngest of the 60 waits for the next pass");
        reconciler.runOnce();
        assertEquals(60, released(orders));
        assertEquals(1000, stock(item));

        // Two rows of one order (two items): asked once, both released.
        long a = item(10), b = item(10), multi = order();
        stale(multi, a, 1);
        stale(multi, b, 2);
        answer(multi, "RESERVATION_FAILED");
        reconciler.runOnce();
        verify(order, times(1)).statusOf(multi);
        assertEquals(10, stock(a));
        assertEquals(10, stock(b));
    }

    /**
     * Starvation: more stale rows than one batch whose orders are still "live"
     * (never acted on, so they stay RESERVED and stay oldest), and one
     * releasable row behind them. It must be released within a bounded number
     * of passes (here 2: 55 live rows, batch 50).
     */
    @Test
    void releasableRowsBehindAFullBatchOfLiveOnesAreReachedWithinTwoPasses() {
        long item = item(1000);
        for (int i = 0; i < 55; i++) {
            long live = order();
            stale(live, item, 1);
            answer(live, "PENDING_PAYMENT");
        }
        long behind = order();
        stale(behind, item, 1);
        answer(behind, "ORDER_EXPIRED");

        reconciler.runOnce();
        reconciler.runOnce();

        assertEquals("RELEASED", status(behind, item), "the releasable row behind 55 live ones");
        assertEquals(1000 - 55, stock(item));
    }

    @Test
    void afterTheEndIsReachedTheNextPassStartsFromTheOldestAgain() {
        long item = item(1000);
        List<Long> live = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            long o = order();
            live.add(o);
            stale(o, item, 1);
            answer(o, "CREATED");
        }

        assertEquals(50, reconciler.runOnce().rows(), "a full batch: the cursor moves on");
        assertEquals(1, reconciler.runOnce().rows(), "the last row; the end is reached");
        verify(order, times(1)).statusOf(live.get(0));

        // The oldest order has ended meanwhile; the next pass wraps and finds it.
        answer(live.get(0), "ORDER_EXPIRED");
        StaleReservationReconciler.Result wrapped = reconciler.runOnce();

        assertEquals(50, wrapped.rows(), "starts from the oldest again");
        verify(order, times(2)).statusOf(live.get(0));
        assertEquals("RELEASED", status(live.get(0), item));
        assertEquals("RESERVED", status(live.get(1), item));
        reconciler.runOnce(); // back to the end, so later tests start from the oldest
    }

    @Test
    void aPoisonOrderInTheMiddleDoesNotBlockTheRowsBehindIt() {
        long item = item(100);
        long a = order(), b = order(), poison = order(), c = order(), d = order();
        for (long o : List.of(a, b, poison, c, d)) {
            stale(o, item, 1);
            answer(o, "RESERVATION_FAILED");
        }
        given(order.statusOf(poison)).willReturn(new OrderStatusClient.Answer.Unreachable("HTTP 500"));

        StaleReservationReconciler.Result first = reconciler.runOnce();
        assertEquals(poison, first.stoppedAt());
        assertEquals("RELEASED", status(a, item));
        assertEquals("RELEASED", status(b, item));
        assertEquals("RESERVED", status(c, item), "the pass stopped at the poison order");
        verify(order, never()).statusOf(c);

        StaleReservationReconciler.Result second = reconciler.runOnce();
        assertNull(second.stoppedAt(), "started after the poison order");
        assertEquals("RELEASED", status(c, item));
        assertEquals("RELEASED", status(d, item));
        verify(order, times(1)).statusOf(poison);

        StaleReservationReconciler.Result third = reconciler.runOnce();
        assertEquals(poison, third.stoppedAt(), "after the wrap the poison order is asked again");
        verify(order, times(2)).statusOf(poison);
        assertEquals("RESERVED", status(poison, item));
        assertEquals(99, stock(item), "4 of the 5 units back, the poison one still held");
        reconciler.runOnce(); // the cursor is past the poison order; this pass reaches the end
    }

    /**
     * The reconciler and the Kafka {@code OrderCancelled} consumer release the
     * same reservation at the same moment, many times over: the stock must come
     * back exactly once each time. Break-and-restore: without
     * {@code AND status = 'RESERVED'} in {@code ReservationEventService.release}
     * this fails (both add the units back).
     */
    @Test
    void reconcilerAndConsumerRacingOnOneReservationGiveTheStockBackExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long item = item(100), other = order(), o = order();
                reserve(other, item, 10, "1 second");
                stale(o, item, 3);
                answer(o, "ORDER_EXPIRED");
                CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
                Future<?> reconcilerSide = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return reconciler.runOnce();
                });
                Future<Integer> consumerSide = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return events.release(o);
                });
                ready.await(5, TimeUnit.SECONDS);
                go.countDown();
                reconcilerSide.get(10, TimeUnit.SECONDS);
                consumerSide.get(10, TimeUnit.SECONDS);

                assertEquals("RELEASED", status(o, item), "round " + round);
                assertEquals(90, stock(item), "round " + round + ": 3 units back exactly once");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ----

    private void answer(long orderId, String status) {
        given(order.statusOf(orderId)).willReturn(new OrderStatusClient.Answer.Status(status));
    }

    private double alerts(String reason) {
        Counter c = meters.find("inventory.reconciler.alerts").tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private double actions(String action) {
        Counter c = meters.find("inventory.reconciler.actions").tag("action", action).counter();
        return c == null ? 0 : c.count();
    }

    private int released(List<Long> orders) {
        return orders.stream().mapToInt(o -> jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservations WHERE order_id = ? AND status = 'RELEASED'",
                Integer.class, o)).sum();
    }

    private long order() {
        return NEXT_ORDER.getAndIncrement();
    }

    /** Days old, each newer than the last, so they come first and in creation order. */
    private void stale(long orderId, long item, int qty) {
        reserve(orderId, item, qty, "1 day " + age.getAndDecrement() + " seconds");
        if (age.get() < 50_000) {
            throw new IllegalStateException("too many stale rows for the age scheme");
        }
    }

    private void reserve(long orderId, long item, int qty, String ago) {
        jdbc.update("INSERT INTO stock_reservations (order_id, flash_sale_item_id, quantity, status, reserved_at) "
                + "VALUES (?, ?, ?, 'RESERVED', now() - ?::interval)", orderId, item, qty, ago);
        jdbc.update("UPDATE flash_sale_items SET available_stock = available_stock - ?, version = version + 1 "
                + "WHERE id = ?", qty, item);
    }

    private long item(int total) {
        Long productId = jdbc.queryForObject("INSERT INTO products (brand_id, name) VALUES (?, ?) RETURNING id",
                Long.class, BRAND_ID, "Reconciler Product " + UUID.randomUUID());
        productIds.add(productId);
        Long saleId = jdbc.queryForObject("INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) "
                        + "VALUES (?, ?, now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE') RETURNING id",
                Long.class, BRAND_ID, "Reconciler Sale " + UUID.randomUUID());
        saleIds.add(saleId);
        Long itemId = jdbc.queryForObject("INSERT INTO flash_sale_items (flash_sale_id, product_id, sale_price, "
                + "total_stock, available_stock) VALUES (?, ?, 3999.00, ?, ?) RETURNING id", Long.class,
                saleId, productId, total, total);
        itemIds.add(itemId);
        return itemId;
    }

    private int stock(long item) {
        return jdbc.queryForObject("SELECT available_stock FROM flash_sale_items WHERE id = ?", Integer.class, item);
    }

    private String status(long orderId, long item) {
        return jdbc.queryForObject("SELECT status FROM stock_reservations WHERE order_id = ? AND flash_sale_item_id = ?",
                String.class, orderId, item);
    }
}
