package com.flashmart.order.service;

import com.flashmart.order.entity.OrderStatus;
import com.flashmart.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final OrderRepository orders;
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

    /**
     * The items the loop still has to reserve, by product id. On a resume this
     * is exactly the set left unpriced, so items already priced are never sent
     * to Inventory again.
     */
    @Transactional(readOnly = true)
    public List<OrderLine> unpricedItems(long orderId) {
        return jdbc.query("SELECT product_id, quantity FROM order_items "
                        + "WHERE order_id = ? AND unit_price IS NULL ORDER BY product_id",
                (rs, n) -> new OrderLine(rs.getLong("product_id"), rs.getInt("quantity")), orderId);
    }

    /**
     * Write-once pricing of one item from a RESERVED answer. Returns the rows
     * changed; {@code 0} is not an error: another loop on the same order priced
     * it first, or the order is no longer {@code CREATED}.
     *
     * <ul>
     *   <li>{@code unit_price IS NULL}: a second loop can never overwrite the
     *       first loop's price.</li>
     *   <li>the {@code orders.status = 'CREATED'} guard (architecture.md section 4):
     *       a failed or expired order is not priced. It is a plain subquery, not
     *       a lock: a CAS committing at the same instant can still let one item
     *       be priced on a just-failed order. Harmless, since that order is
     *       terminal and its amount CAS is status-guarded.</li>
     * </ul>
     * V3's CHECKs back this up: all-or-none pricing, subtotal = price x quantity.
     */
    @Transactional
    public int priceItem(long orderId, long productId, String productName, BigDecimal unitPrice) {
        return jdbc.update("UPDATE order_items SET product_name = ?, unit_price = ?, subtotal = ? * quantity "
                        + "WHERE order_id = ? AND product_id = ? AND unit_price IS NULL "
                        + "AND EXISTS (SELECT 1 FROM orders WHERE id = ? AND status = 'CREATED')",
                productName, unitPrice, unitPrice, orderId, productId, orderId);
    }

    /**
     * {@code CREATED -> RESERVATION_FAILED} and its {@code OrderCancelled}, in
     * one transaction. The outbox row is written ONLY if this caller's CAS won:
     * two loops failing the same order (a retry racing the first request) must
     * produce one event, not two. Returns whether this caller won.
     *
     * <p>Payload is flat and bare, as every FlashMart event: no envelope, no
     * event id. Inventory releases every reservation of the order when it
     * consumes it (relay and consumer are not built yet).
     */
    @Transactional
    public boolean fail(long orderId, long buyerId, String failureDetail) {
        int won = orders.markReservationFailed(orderId, failureDetail);
        if (won == 1) {
            jdbc.update("INSERT INTO outbox (aggregate_id, event_type, payload_json) VALUES (?, ?, ?::jsonb)",
                    orderId, "OrderCancelled",
                    "{\"order_id\":" + orderId + ",\"reason\":\"RESERVATION_FAILED\",\"buyer_id\":" + buyerId + "}");
        }
        return won == 1;
    }

    /**
     * Sets {@code amount = SUM(subtotal)} through the existing write-once CAS,
     * only once no item is left unpriced. Returns the rows changed; {@code 0}
     * means another loop set it, the order is no longer {@code CREATED}, or an
     * item is still unpriced. The caller re-reads either way.
     */
    @Transactional
    public int setAmountFromItems(long orderId) {
        BigDecimal sum = jdbc.queryForObject("SELECT CASE WHEN count(*) FILTER (WHERE unit_price IS NULL) = 0 "
                + "THEN sum(subtotal) END FROM order_items WHERE order_id = ?", BigDecimal.class, orderId);
        return sum == null ? 0 : orders.setAmount(orderId, sum);
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
