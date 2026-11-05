package com.flashmart.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
}
