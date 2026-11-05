package com.flashmart.inventory.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReservationEventService} on real Postgres (Day 7, points 1 and 2).
 *
 * <p>Calls the service directly: NO Kafka, NO listener, NO offset. What these
 * tests cannot see: the message path (see {@code InventoryConsumerKafkaTest})
 * and a real crash between the commit and the offset. NOT {@code @Transactional}
 * (each call must really commit); rows are seeded with JdbcTemplate and
 * removed by id. Order ids start at 880_000_000 and are unique per test
 * because {@code stock_reservations.order_id} has no FK and the dev database
 * is shared.
 */
@SpringBootTest
class ReservationEventServiceTest {

    private static final long BRAND_ID = 9101L;
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ORDER = new java.util.concurrent.atomic.AtomicLong(880_000_000L);

    @Autowired
    private ReservationEventService service;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> saleIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ReservationEventService.class)).addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        ((Logger) LoggerFactory.getLogger(ReservationEventService.class)).detachAppender(logs);
        for (Long itemId : itemIds) {
            jdbc.update("DELETE FROM stock_reservations WHERE flash_sale_item_id = ?", itemId);
            jdbc.update("DELETE FROM flash_sale_items WHERE id = ?", itemId);
        }
        saleIds.forEach(id -> jdbc.update("DELETE FROM flash_sales WHERE id = ?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
    }

    // ---- release ----

    @Test
    void releaseFlipsReservedToReleasedAndGivesTheStockBack() {
        long item = item(100);
        long order = order();
        reserve(order, item, 3);
        assertEquals(97, stock(item));

        assertEquals(1, service.release(order));

        assertEquals("RELEASED", status(order, item));
        assertEquals(100, stock(item));
        assertEquals(1L, version(item), "the raw UPDATE must bump version itself");
    }

    @Test
    void releaseOfAMultiItemOrderGivesBackEveryItem() {
        long a = item(100), b = item(50), c = item(10);
        long order = order();
        reserve(order, a, 2);
        reserve(order, b, 5);
        reserve(order, c, 1);

        assertEquals(3, service.release(order));

        assertEquals(100, stock(a));
        assertEquals(50, stock(b));
        assertEquals(10, stock(c));
        assertEquals("RELEASED", status(order, a));
        assertEquals("RELEASED", status(order, b));
        assertEquals("RELEASED", status(order, c));
    }

    @Test
    void releaseTouchesOnlyThisOrdersRows() {
        long item = item(100);
        long mine = order(), other = order();
        reserve(mine, item, 2);
        reserve(other, item, 4);

        service.release(mine);

        assertEquals("RELEASED", status(mine, item));
        assertEquals("RESERVED", status(other, item));
        assertEquals(96, stock(item), "only my 2 units come back");
    }

    @Test
    void releaseTwiceInARowAddsStockOnce() {
        long item = item(100);
        long order = order();
        reserve(order, item, 4);

        assertEquals(1, service.release(order));
        assertEquals(0, service.release(order), "the duplicate matches 0 rows");

        assertEquals(100, stock(item));
        assertEquals(1L, version(item), "the duplicate must not touch the item at all");
    }

    // The same OrderCancelled handled by two threads at once. The loser's UPDATE
    // blocks on the winner's row lock, re-checks status = 'RESERVED' after the
    // commit and matches 0 rows. 50 rounds on fresh rows. A contention test on
    // one JVM, not a load test.
    @Test
    void releaseByTwoThreadsAtOnceAddsStockOnce() throws Exception {
        List<String> broken = new ArrayList<>();
        for (int round = 1; round <= 50; round++) {
            long item = item(100);
            long order = order();
            reserve(order, item, 5);

            int[] released = raceRelease(order);

            int total = released[0] + released[1];
            if (total != 1 || stock(item) != 100 || version(item) != 1L) {
                broken.add("round " + round + ": released " + released[0] + "+" + released[1]
                        + ", stock " + stock(item) + " (want 100), version " + version(item) + " (want 1)");
            }
        }
        assertTrue(broken.isEmpty(), broken.size() + " of 50 rounds broke:\n" + String.join("\n", broken));
    }

    @Test
    void releaseNeverTouchesAConfirmedReservation() {
        long item = item(100);
        long order = order();
        reserve(order, item, 3);
        service.confirm(order);

        assertEquals(0, service.release(order));

        assertEquals("CONFIRMED", status(order, item));
        assertEquals(97, stock(item), "paid stock stays taken");
    }

    @Test
    void releaseOfAnOrderInventoryNeverHeardOfChangesNothing() {
        long item = item(100);
        assertEquals(0, service.release(order()));
        assertEquals(100, stock(item));
    }

    // ---- confirm ----

    @Test
    void confirmFlipsEveryReservedRow() {
        long a = item(100), b = item(100);
        long order = order();
        reserve(order, a, 1);
        reserve(order, b, 2);

        assertEquals(2, service.confirm(order));

        assertEquals("CONFIRMED", status(order, a));
        assertEquals("CONFIRMED", status(order, b));
        assertEquals(99, stock(a), "confirming does not move stock");
        assertEquals(98, stock(b));
    }

    @Test
    void confirmDuplicateIsQuiet() {
        long item = item(100);
        long order = order();
        reserve(order, item, 1);
        service.confirm(order);
        logs.list.clear();

        assertEquals(0, service.confirm(order));

        assertEquals("CONFIRMED", status(order, item));
        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)),
                "a duplicate must not log WARN or ERROR: " + logs.list);
    }

    @Test
    void confirmOnAReleasedReservationLogsErrorAndChangesNothing() {
        long item = item(100);
        long order = order();
        reserve(order, item, 3);
        service.release(order);
        logs.list.clear();

        assertEquals(0, service.confirm(order));

        assertEquals("RELEASED", status(order, item), "never flipped back");
        assertEquals(100, stock(item));
        assertErrorMentions(order);
    }

    @Test
    void confirmOnAMixedOrderWithAReleasedRowLogsErrorAndChangesNothing() {
        long a = item(100), b = item(100);
        long order = order();
        reserve(order, a, 1);
        reserve(order, b, 1);
        // Row b was released on its own (hand-set: release() does whole orders).
        jdbc.update("UPDATE stock_reservations SET status = 'RELEASED' WHERE order_id = ? AND flash_sale_item_id = ?", order, b);
        jdbc.update("UPDATE stock_reservations SET status = 'CONFIRMED' WHERE order_id = ? AND flash_sale_item_id = ?", order, a);

        assertEquals(0, service.confirm(order));

        assertEquals("CONFIRMED", status(order, a));
        assertEquals("RELEASED", status(order, b));
        assertErrorMentions(order);
    }

    @Test
    void confirmWithNoReservationLogsErrorAndChangesNothing() {
        long item = item(100);
        long order = order();

        assertEquals(0, service.confirm(order));

        assertEquals(100, stock(item));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stock_reservations WHERE order_id = ?", Integer.class, order));
        assertErrorMentions(order);
    }

    // ---- helpers ----

    private void assertErrorMentions(long orderId) {
        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), "want exactly one ERROR, got: " + logs.list);
        assertTrue(errors.get(0).getFormattedMessage().contains("order " + orderId),
                "the ERROR must carry the order id: " + errors.get(0).getFormattedMessage());
    }

    /** Two threads, one barrier, both release the same order. Returns what each released. */
    private int[] raceRelease(long order) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return service.release(order);
                }));
            }
            ready.await();
            go.countDown();
            return new int[]{futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    private long order() {
        return NEXT_ORDER.getAndIncrement();
    }

    /** A live-or-not sale does not matter to release/confirm, so the sale window is irrelevant here. */
    private long item(int total) {
        Long productId = jdbc.queryForObject("INSERT INTO products (brand_id, name) VALUES (?, ?) RETURNING id",
                Long.class, BRAND_ID, "Event Service Product " + UUID.randomUUID());
        productIds.add(productId);
        Long saleId = jdbc.queryForObject("INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) "
                        + "VALUES (?, ?, now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE') RETURNING id",
                Long.class, BRAND_ID, "Event Service Sale " + UUID.randomUUID());
        saleIds.add(saleId);
        Long itemId = jdbc.queryForObject("INSERT INTO flash_sale_items (flash_sale_id, product_id, sale_price, total_stock, available_stock) "
                + "VALUES (?, ?, 3999.00, ?, ?) RETURNING id", Long.class, saleId, productId, total, total);
        itemIds.add(itemId);
        return itemId;
    }

    /** What reserve() leaves behind: a RESERVED row and the stock already taken. */
    private void reserve(long order, long item, int qty) {
        jdbc.update("INSERT INTO stock_reservations (order_id, flash_sale_item_id, quantity, status) VALUES (?, ?, ?, 'RESERVED')",
                order, item, qty);
        jdbc.update("UPDATE flash_sale_items SET available_stock = available_stock - ?, version = 0 WHERE id = ?", qty, item);
    }

    private int stock(long item) {
        return jdbc.queryForObject("SELECT available_stock FROM flash_sale_items WHERE id = ?", Integer.class, item);
    }

    private long version(long item) {
        return jdbc.queryForObject("SELECT version FROM flash_sale_items WHERE id = ?", Long.class, item);
    }

    private String status(long order, long item) {
        return jdbc.queryForObject("SELECT status FROM stock_reservations WHERE order_id = ? AND flash_sale_item_id = ?",
                String.class, order, item);
    }
}
