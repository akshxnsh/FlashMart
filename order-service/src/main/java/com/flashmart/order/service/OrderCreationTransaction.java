package com.flashmart.order.service;

import com.flashmart.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The short database transactions of the create path. Each public method is
 * one transaction, called through the Spring proxy from the (non-transactional)
 * orchestrator, so no transaction is ever open across a call to Inventory.
 *
 * <p>Writes here are native SQL through {@link JdbcTemplate}: it joins the JPA
 * transaction, bypasses the persistence context (so no stale entity can be
 * flushed over a CAS), and translates a unique violation into Spring's
 * {@code DataIntegrityViolationException}.
 */
@Component
@RequiredArgsConstructor
public class OrderCreationTransaction {

    private static final String SNAPSHOT = "SELECT id, buyer_id, flash_sale_id, status, amount, "
            + "clock_timestamp() < expires_at AS before_deadline FROM orders ";

    private static final RowMapper<OrderSnapshot> SNAPSHOT_ROW = (rs, n) -> new OrderSnapshot(
            rs.getLong("id"), rs.getLong("buyer_id"), rs.getLong("flash_sale_id"),
            OrderStatus.valueOf(rs.getString("status")), rs.getBigDecimal("amount"),
            rs.getBoolean("before_deadline"));

    private final JdbcTemplate jdbc;
    private final CreateOrderSettings settings;

    /**
     * Inserts the order and its unpriced items in one transaction and returns
     * the new id.
     *
     * <p>{@code expires_at} is stamped by the database, in this statement, with
     * {@code clock_timestamp()}: {@code now()} is frozen at the start of the
     * transaction, so in a long transaction it would give a shorter window than
     * promised (decisions.md, "Who stamps a time column"). A JPA {@code save()}
     * would stamp it from the pod clock instead.
     *
     * <p>Either unique index on {@code orders} can reject this insert; the
     * caller handles that after this transaction has rolled back.
     */
    @Transactional
    public long insert(long buyerId, long flashSaleId, String idempotencyKey, List<OrderLine> lines) {
        Long id = jdbc.queryForObject(
                "INSERT INTO orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at) "
                        + "VALUES (?, ?, 'CREATED', ?, clock_timestamp() + make_interval(secs => ?)) "
                        + "RETURNING id",
                Long.class, buyerId, flashSaleId, idempotencyKey, settings.deadlineSeconds(lines.size()));
        jdbc.batchUpdate("INSERT INTO order_items (order_id, product_id, quantity) VALUES (?, ?, ?)",
                lines, lines.size(), (ps, line) -> {
                    ps.setLong(1, id);
                    ps.setLong(2, line.productId());
                    ps.setInt(3, line.quantity());
                });
        return id;
    }

    /** The order this buyer created with this key, if any. Rule (a): a replay. */
    @Transactional(readOnly = true)
    public Optional<OrderSnapshot> findByKey(long buyerId, String idempotencyKey) {
        return jdbc.query(SNAPSHOT + "WHERE buyer_id = ? AND idempotency_key = ?",
                SNAPSHOT_ROW, buyerId, idempotencyKey).stream().findFirst();
    }

    /**
     * The buyer's live-or-paid order for this sale, if any. Rule (b). The
     * predicate is the one {@code uk_orders_buyer_sale_live_or_paid} (V6)
     * enforces, so at most one row can match.
     */
    @Transactional(readOnly = true)
    public Optional<OrderSnapshot> findLiveOrPaid(long buyerId, long flashSaleId) {
        return jdbc.query(SNAPSHOT + "WHERE buyer_id = ? AND flash_sale_id = ? "
                        + "AND status IN ('CREATED', 'PENDING_PAYMENT', 'PAID')",
                SNAPSHOT_ROW, buyerId, flashSaleId).stream().findFirst();
    }

    /** A fresh read, for reporting the real state after losing a CAS. */
    @Transactional(readOnly = true)
    public OrderSnapshot read(long orderId) {
        return jdbc.queryForObject(SNAPSHOT + "WHERE id = ?", SNAPSHOT_ROW, orderId);
    }

    /** {@code product_id -> quantity}, the stored half of the key-reuse comparison. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> quantitiesOf(long orderId) {
        Map<Long, Integer> quantities = new TreeMap<>();
        jdbc.query("SELECT product_id, quantity FROM order_items WHERE order_id = ?",
                rs -> {
                    quantities.put(rs.getLong("product_id"), rs.getInt("quantity"));
                }, orderId);
        return quantities;
    }
}
