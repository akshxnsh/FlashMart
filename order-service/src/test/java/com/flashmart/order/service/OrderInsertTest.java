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
    void maxLinesIsDerivedFromTheBlockingPolicyAndThePerCallTimeout() {
        assertEquals(30, settings.maxLines()); // floor(60 s / 2 s)
        assertEquals(2.0 * 3 + 5, settings.deadlineSeconds(3));
    }

    @Test
    void theDeadlineIsPerCallTimeoutTimesLinesPlusMarginOnTheDatabaseClock() {
        long id = transaction.insert(4101L, 7L, "key-a",
                List.of(new OrderLine(11L, 1), new OrderLine(12L, 2), new OrderLine(13L, 5)));

        // created_at defaults to clock_timestamp() in the same statement, so
        // the gap is the deadline length alone: 2 s * 3 + 5 s = 11 s.
        double gap = jdbc.queryForObject(
                "select extract(epoch from expires_at - created_at) from orders where id = ?", Double.class, id);
        assertEquals(11.0, gap, 0.5);

        // And it was stamped by the database now, not by some other clock.
        double remaining = jdbc.queryForObject(
                "select extract(epoch from expires_at - clock_timestamp()) from orders where id = ?",
                Double.class, id);
        assertTrue(remaining > 9.0 && remaining <= 11.0, "remaining " + remaining);
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
