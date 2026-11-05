package com.flashmart.order.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The create path's insert. Rolled back: nothing commits, so no cleanup is
 * needed. Read back with native SQL so the database's own values are checked.
 */
@SpringBootTest
@Transactional
class OrderInsertTest {

    @Autowired
    private OrderCreationTransaction transaction;

    @Autowired
    private CreateOrderSettings settings;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void maxLinesAndTheDeadlineAreDerivedFromConfig() {
        // perReserveCall = 1 s + 2 s = 3 s; paymentBudget = 2 x (1 s + 2 s) + 0.5 s = 6.5 s
        assertEquals(17, settings.maxLines()); // floor((60 s - 6.5 s) / 3 s)
        assertEquals(20.5, settings.deadlineSeconds(3)); // 3 x 3 + 6.5 + 5
        assertEquals(62.5, settings.deadlineSeconds(17)); // 17 x 3 + 6.5 + 5
    }

    @Test
    void theDerivationFollowsItsInputsNotConstants() {
        // inventory 500 + 1500 = 2 s per call; payment 1 attempt x (500 + 500) = 1 s, no wait;
        // policy 31 s, margin 2 s: maxLines = floor((31 - 1) / 2) = 15; 4 lines = 8 + 1 + 2 = 11 s.
        CreateOrderSettings other = new CreateOrderSettings(500, 1500, 500, 500, 1, 0, 31_000, 2_000,
                new java.math.BigDecimal("10.00"));
        assertEquals(15, other.maxLines());
        assertEquals(11.0, other.deadlineSeconds(4));
    }

    @Test
    void aPolicyTooSmallForOneLineIsRejectedAtStartup() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new CreateOrderSettings(1000, 2000, 1000, 2000, 2, 500, 9_000, 5_000,
                        new java.math.BigDecimal("49.00"))); // 9 s - 6.5 s = 2.5 s < one 3 s call
    }

    @Test
    void theDeadlineIsCallsPlusPaymentBudgetPlusMarginOnTheDatabaseClock() {
        long id = transaction.insert(4101L, 7L, "key-a",
                List.of(new OrderLine(11L, 1), new OrderLine(12L, 2), new OrderLine(13L, 5)));

        // created_at defaults to clock_timestamp() in the same statement, so
        // the gap is the deadline length alone: 3 s * 3 + 6.5 s + 5 s = 20.5 s.
        double gap = jdbc.queryForObject(
                "select extract(epoch from expires_at - created_at) from orders where id = ?", Double.class, id);
        assertEquals(20.5, gap, 0.5);

        // And it was stamped by the database now, not by some other clock.
        double remaining = jdbc.queryForObject(
                "select extract(epoch from expires_at - clock_timestamp()) from orders where id = ?",
                Double.class, id);
        assertTrue(remaining > 18.5 && remaining <= 20.5, "remaining " + remaining);
    }

    @Test
    void theDeadlineOfAFullOrderOfMaxLinesIs62Point5Seconds() {
        List<OrderLine> lines = java.util.stream.LongStream.rangeClosed(1, settings.maxLines())
                .mapToObj(p -> new OrderLine(p, 1)).toList();
        long id = transaction.insert(4103L, 7L, "key-full", lines);

        double gap = jdbc.queryForObject(
                "select extract(epoch from expires_at - created_at) from orders where id = ?", Double.class, id);
        assertEquals(17, lines.size());
        assertEquals(62.5, gap, 0.5);
    }

    @Test
    void theOrderStartsCreatedWithNoAmountAndUnpricedItems() {
        long id = transaction.insert(4102L, 7L, "key-b", List.of(new OrderLine(11L, 2), new OrderLine(12L, 1)));

        Map<String, Object> order = jdbc.queryForMap("select * from orders where id = ?", id);
        assertEquals("CREATED", order.get("status"));
        assertNull(order.get("amount"));
        assertNull(order.get("payment_ref"));
        assertEquals("key-b", order.get("idempotency_key"));

        List<Map<String, Object>> items = jdbc.queryForList(
                "select product_id, quantity, product_name, unit_price, subtotal from order_items "
                        + "where order_id = ? order by product_id", id);
        assertEquals(2, items.size());
        assertEquals(11L, items.get(0).get("product_id"));
        assertEquals(2, items.get(0).get("quantity"));
        for (Map<String, Object> item : items) {
            assertNull(item.get("product_name"));
            assertNull(item.get("unit_price"));
            assertNull(item.get("subtotal"));
        }
    }
}
