package com.flashmart.order;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Cleanup for tests that commit. Each such test class owns a range of buyer
 * ids no other class uses; this finds that range's order ids and deletes their
 * outbox rows, items and orders by id, in foreign-key order. A leftover
 * CREATED order would otherwise trip {@code uk_orders_buyer_sale_live_or_paid}
 * in the next test.
 */
public final class TestRows {

    private TestRows() {
    }

    public static void deleteOrdersOfBuyers(JdbcTemplate jdbc, long fromBuyer, long toBuyer) {
        List<Long> ids = jdbc.queryForList(
                "select id from orders where buyer_id between ? and ?", Long.class, fromBuyer, toBuyer);
        for (Long id : ids) {
            jdbc.update("delete from outbox where aggregate_id = ?", id);
            jdbc.update("delete from order_items where order_id = ?", id);
            jdbc.update("delete from orders where id = ?", id);
        }
    }
}
