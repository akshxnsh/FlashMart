package com.flashmart.inventory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * What Inventory does when Order tells it an order's fate: give the stock back
 * ({@code OrderCancelled}) or lock the reservation in ({@code PaymentConfirmed}).
 * Derived from decisions.md, "Day 7", points 1 and 2.
 *
 * <p>Both methods are safe to run twice, or twice at once, for the same order.
 * Kafka delivers at least once, so a duplicate WILL arrive. The safety does not
 * come from checking first in Java; it comes from one conditional
 * {@code UPDATE ... WHERE status = 'RESERVED'}. Under READ COMMITTED a second
 * UPDATE for the same rows waits for the first to commit, re-checks the WHERE,
 * and finds the status already changed: it matches 0 rows and so adds 0 stock.
 *
 * <p>Plain {@link JdbcTemplate}, not the entities: it joins the surrounding
 * transaction and never goes through a persistence context. Every
 * {@code flash_sale_items} write bumps {@code version} by hand, because a raw
 * UPDATE does not (CLAUDE.md, raw SQL trap). The table's
 * {@code CHECK (available_stock <= total_stock)} is left in place as the
 * last line of defence if this reasoning is ever wrong.
 */
@Slf4j
@Service
public class ReservationEventService {

    private final JdbcTemplate jdbc;

    public ReservationEventService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Released(long flashSaleItemId, int quantity) {}

    /**
     * {@code OrderCancelled}: flip this order's {@code RESERVED} rows to
     * {@code RELEASED}, then add back exactly the quantities of the rows that
     * flip returned, all in ONE transaction. A crash or error between the two
     * steps rolls both back, so stock is never given back without the status
     * change, or the status changed without the stock.
     *
     * <p>A {@code CONFIRMED} row is not matched, so a cancel that arrives after
     * a payment never touches paid stock. A duplicate matches 0 rows.
     *
     * @return how many reservation rows this call released (0 for a duplicate)
     */
    @Transactional
    public int release(long orderId) {
        List<Released> released = jdbc.query("""
                        UPDATE stock_reservations
                           SET status = 'RELEASED'
                         WHERE order_id = ?
                           AND status = 'RESERVED'
                        RETURNING flash_sale_item_id, quantity
                        """,
                (rs, i) -> new Released(rs.getLong("flash_sale_item_id"), rs.getInt("quantity")),
                orderId);

        // Ascending item id, so two multi-item releases that touch the same
        // items lock the item rows in the same order and cannot deadlock.
        released.stream()
                .sorted(Comparator.comparingLong(Released::flashSaleItemId))
                .forEach(r -> jdbc.update("""
                        UPDATE flash_sale_items
                           SET available_stock = available_stock + ?,
                               version = version + 1
                         WHERE id = ?
                        """, r.quantity(), r.flashSaleItemId()));

        if (released.isEmpty()) {
            log.info("OrderCancelled for order {}: nothing to release (duplicate, already confirmed, or no reservation)", orderId);
        } else {
            log.info("OrderCancelled for order {}: released {} reservation(s)", orderId, released.size());
        }
        return released.size();
    }

    /**
     * {@code PaymentConfirmed}: {@code RESERVED -> CONFIRMED}. When that
     * matches nothing, look at what is there before deciding it is harmless:
     * all {@code CONFIRMED} is a duplicate (quiet); any {@code RELEASED}, or no
     * rows at all, means the stock was given back or never held for a payment
     * that was taken, which is logged as ERROR and left alone. It is never
     * flipped back to {@code CONFIRMED}: that would hide an oversell (the unit
     * may already belong to another buyer). Inventory does not guess.
     *
     * @return how many reservation rows this call confirmed (0 for a duplicate or an anomaly)
     */
    @Transactional
    public int confirm(long orderId) {
        int confirmed = jdbc.update("""
                UPDATE stock_reservations
                   SET status = 'CONFIRMED'
                 WHERE order_id = ?
                   AND status = 'RESERVED'
                """, orderId);
        if (confirmed > 0) {
            log.info("PaymentConfirmed for order {}: confirmed {} reservation(s)", orderId, confirmed);
            return confirmed;
        }

        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM stock_reservations WHERE order_id = ?", String.class, orderId);
        if (!statuses.isEmpty() && statuses.stream().allMatch("CONFIRMED"::equals)) {
            log.info("PaymentConfirmed for order {}: already confirmed (duplicate), no change", orderId);
        } else if (statuses.isEmpty()) {
            log.error("PaymentConfirmed for order {} but Inventory holds NO reservation for it. "
                    + "A payment was taken for stock that is not held. Nothing changed; needs a human.", orderId);
        } else {
            log.error("PaymentConfirmed for order {} but its reservations are {} (not all CONFIRMED; at least one "
                    + "was RELEASED). Not flipped back, because the unit may already belong to another buyer. "
                    + "Nothing changed; needs a human.", orderId, statuses);
        }
        return 0;
    }
}
