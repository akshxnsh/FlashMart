package com.flashmart.inventory.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flashmart.inventory.dto.reserve.ReserveOutcome;
import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.exception.QuantityMismatchException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests of {@code reserve}, single-threaded, on real Postgres.
 *
 * <p>NOT {@code @Transactional}: a rolled-back test transaction would swallow
 * the very commit and rollback this service is built on (the OUT_OF_STOCK test
 * needs the real rollback to prove the inserted reservation disappears). So
 * data is committed through JdbcTemplate and each test removes exactly the
 * rows it created, by id, in foreign-key order. No blanket DELETE: the
 * development database may hold seed data.
 *
 * <p>Sale windows are written in SQL relative to the database's now(), the
 * same clock reserve uses, never the JVM's.
 *
 * <p>Not covered here (Day 3): concurrent calls and the unique-violation catch.
 */
@SpringBootTest
class ReservationServiceTest {

    private static final long BRAND_ID = 9001L;
    private static final String PRODUCT_NAME_PREFIX = "Reserve Test Product ";

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> saleIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long itemId : itemIds) {
            jdbc.update("DELETE FROM stock_reservations WHERE flash_sale_item_id = ?", itemId);
            jdbc.update("DELETE FROM flash_sale_items WHERE id = ?", itemId);
        }
        saleIds.forEach(id -> jdbc.update("DELETE FROM flash_sales WHERE id = ?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
    }

    // ---- RESERVED -------------------------------------------------------

    @Test
    void firstCallReservesAndDecrementsStockAndBumpsVersion() {
        Seed s = seed("-1 hour", "+1 hour", 10);

        ReserveResponse response = reservationService.reserve(request(1L, s, 2));

        assertEquals(ReserveOutcome.RESERVED, response.outcome());
        assertNotNull(response.reservationId());
        assertEquals("3999.00", response.salePrice().toPlainString());
        assertEquals(BRAND_ID, response.brandId());
        assertEquals(s.productName, response.productName());
        assertEquals(8, stock(s));
        assertEquals(1L, version(s));
        assertEquals(1, reservationCount(s));
    }

    // Boundary of the stock guard: taking exactly what is left must succeed.
    @Test
    void reservingExactlyTheRemainingStockSucceedsAndLeavesZero() {
        Seed s = seed("-1 hour", "+1 hour", 2);

        ReserveResponse response = reservationService.reserve(request(1L, s, 2));

        assertEquals(ReserveOutcome.RESERVED, response.outcome());
        assertEquals(0, stock(s));
        assertEquals(1L, version(s));
        assertEquals(1, reservationCount(s));
    }

    @Test
    void replayReturnsTheIdenticalResultAndLeavesStockAlone() {
        Seed s = seed("-1 hour", "+1 hour", 10);
        ReserveResponse first = reservationService.reserve(request(1L, s, 2));

        ReserveResponse replay = reservationService.reserve(request(1L, s, 2));

        assertEquals(first, replay);
        assertEquals(8, stock(s));
        assertEquals(1L, version(s));
        assertEquals(1, reservationCount(s));
    }

    @Test
    void replayWithADifferentQuantityIsAMismatchAndChangesNothing() {
        Seed s = seed("-1 hour", "+1 hour", 10);
        reservationService.reserve(request(1L, s, 2));

        assertThrows(QuantityMismatchException.class,
                () -> reservationService.reserve(request(1L, s, 3)));

        assertEquals(8, stock(s));
        assertEquals(1L, version(s));
        assertEquals(1, reservationCount(s));
    }

    @Test
    void releasedReservationAnswersReservationReleasedAndNeverReservesAgain() {
        Seed s = seed("-1 hour", "+1 hour", 10);
        reservationService.reserve(request(1L, s, 2));
        jdbc.update("UPDATE stock_reservations SET status = 'RELEASED' WHERE flash_sale_item_id = ?", s.itemId);

        ReserveResponse response = reservationService.reserve(request(1L, s, 2));

        assertEquals(ReserveOutcome.RESERVATION_RELEASED, response.outcome());
        assertNull(response.reservationId());
        assertEquals(8, stock(s));
        assertEquals(1, reservationCount(s));
    }

    // ---- not reserved ---------------------------------------------------

    @Test
    void unknownItemAnswersItemNotInSaleAndLogsAWarn() {
        Seed s = seed("-1 hour", "+1 hour", 10);
        Logger logger = (Logger) LoggerFactory.getLogger(ReservationTransaction.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReserveResponse response = reservationService.reserve(
                    new ReserveRequest(1L, s.saleId, s.productId + 1_000_000L, 1));

            assertEquals(ReserveOutcome.ITEM_NOT_IN_SALE, response.outcome());
            assertTrue(appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.WARN && e.getFormattedMessage().contains("ITEM_NOT_IN_SALE")));
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(10, stock(s));
        assertEquals(0, reservationCount(s));
    }

    @Test
    void outOfStockLeavesNoReservationRowAndStockUnchanged() {
        Seed s = seed("-1 hour", "+1 hour", 3);

        ReserveResponse response = reservationService.reserve(request(1L, s, 4));

        assertEquals(ReserveOutcome.OUT_OF_STOCK, response.outcome());
        assertEquals(3, stock(s));
        assertEquals(0L, version(s));
        assertEquals(0, reservationCount(s));
    }

    @Test
    void upcomingSaleIsNotLive() {
        Seed s = seed("+1 hour", "+2 hours", 10);

        ReserveResponse response = reservationService.reserve(request(1L, s, 1));

        assertEquals(ReserveOutcome.SALE_NOT_LIVE, response.outcome());
        assertEquals(10, stock(s));
        assertEquals(0, reservationCount(s));
    }

    @Test
    void endedSaleIsNotLive() {
        Seed s = seed("-2 hours", "-1 hour", 10);

        ReserveResponse response = reservationService.reserve(request(1L, s, 1));

        assertEquals(ReserveOutcome.SALE_NOT_LIVE, response.outcome());
        assertEquals(10, stock(s));
        assertEquals(0, reservationCount(s));
    }

    // The window covers now, so only the status can make this sale not live.
    @Test
    void cancelledSaleWithAWindowCoveringNowIsNotLive() {
        Seed s = seed("-1 hour", "+1 hour", 10, "CANCELLED");

        ReserveResponse response = reservationService.reserve(request(1L, s, 1));

        assertEquals(ReserveOutcome.SALE_NOT_LIVE, response.outcome());
        assertEquals(10, stock(s));
        assertEquals(0L, version(s));
        assertEquals(0, reservationCount(s));
    }

    // ---- evaluation order -----------------------------------------------

    @Test
    void replayAfterTheSaleEndedStillReturnsTheExistingReservation() {
        Seed s = seed("-1 hour", "+1 hour", 10);
        ReserveResponse first = reservationService.reserve(request(1L, s, 2));
        jdbc.update("UPDATE flash_sales SET start_time = now() - interval '2 hours', "
                + "end_time = now() - interval '1 hour' WHERE id = ?", s.saleId);

        ReserveResponse replay = reservationService.reserve(request(1L, s, 2));

        assertEquals(ReserveOutcome.RESERVED, replay.outcome());
        assertEquals(first, replay);
        assertEquals(8, stock(s));
        assertEquals(1, reservationCount(s));
    }

    // ---- helpers --------------------------------------------------------

    private record Seed(long saleId, long productId, long itemId, String productName) {
    }

    /** Offsets are Postgres interval text, applied to the database's now(). */
    private Seed seed(String startOffset, String endOffset, int stock) {
        return seed(startOffset, endOffset, stock, "ACTIVE");
    }

    private Seed seed(String startOffset, String endOffset, int stock, String status) {
        String productName = PRODUCT_NAME_PREFIX + UUID.randomUUID();
        Long productId = jdbc.queryForObject(
                "INSERT INTO products (brand_id, name) VALUES (?, ?) RETURNING id",
                Long.class, BRAND_ID, productName);
        productIds.add(productId);

        Long saleId = jdbc.queryForObject(
                "INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) "
                        + "VALUES (?, ?, now() + ?::interval, now() + ?::interval, ?) RETURNING id",
                Long.class, BRAND_ID, "Reserve Test Sale " + UUID.randomUUID(), startOffset, endOffset, status);
        saleIds.add(saleId);

        Long itemId = jdbc.queryForObject(
                "INSERT INTO flash_sale_items (flash_sale_id, product_id, sale_price, total_stock, available_stock) "
                        + "VALUES (?, ?, 3999.00, ?, ?) RETURNING id",
                Long.class, saleId, productId, stock, stock);
        itemIds.add(itemId);
        return new Seed(saleId, productId, itemId, productName);
    }

    private ReserveRequest request(long orderId, Seed s, int quantity) {
        return new ReserveRequest(orderId, s.saleId, s.productId, quantity);
    }

    private int stock(Seed s) {
        return jdbc.queryForObject(
                "SELECT available_stock FROM flash_sale_items WHERE id = ?", Integer.class, s.itemId);
    }

    private long version(Seed s) {
        return jdbc.queryForObject(
                "SELECT version FROM flash_sale_items WHERE id = ?", Long.class, s.itemId);
    }

    private int reservationCount(Seed s) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM stock_reservations WHERE flash_sale_item_id = ?", Integer.class, s.itemId);
    }
}
