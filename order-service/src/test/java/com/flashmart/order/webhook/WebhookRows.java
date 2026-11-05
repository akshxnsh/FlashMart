package com.flashmart.order.webhook;

import com.flashmart.order.entity.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order rows in a chosen state, inserted with native SQL (the path production
 * uses for CAS-owned columns), and the reads the webhook tests assert on.
 *
 * <p>Every order has two priced items: 1 x 200.00 + 2 x 125.00 = 450.00, plus
 * the 49.00 fee = {@link #AMOUNT}. Statuses that V2/V7 require to carry an
 * amount and a ref get both; {@code CREATED} gets neither;
 * {@code PAYMENT_UNAVAILABLE} gets an amount and no ref (initiate never
 * returned), the shape of the crash window the webhook-by-order-id rule exists for.
 */
final class WebhookRows {

    static final BigDecimal AMOUNT = new BigDecimal("499.00");
    static final long SALE = 7L;

    private final JdbcTemplate jdbc;

    WebhookRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String refOf(long buyer) {
        return "pay_held_" + buyer;
    }

    long order(long buyer, OrderStatus status) {
        boolean priced = status != OrderStatus.CREATED;
        boolean hasRef = status != OrderStatus.CREATED && status != OrderStatus.PAYMENT_UNAVAILABLE
                && status != OrderStatus.RESERVATION_FAILED;
        Long id = jdbc.queryForObject("INSERT INTO orders (buyer_id, flash_sale_id, status, idempotency_key, "
                        + "expires_at, amount, payment_ref) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                buyer, SALE, status.name(), "key-" + buyer,
                java.sql.Timestamp.from(Instant.parse("2030-01-01T10:00:00Z")),
                priced ? AMOUNT : null, hasRef ? refOf(buyer) : null);
        item(id, 11L, priced ? "Runner" : null, 1, priced ? "200.00" : null);
        item(id, 12L, priced ? "Sock" : null, 2, priced ? "125.00" : null);
        return id;
    }

    private void item(long orderId, long productId, String name, int quantity, String unitPrice) {
        BigDecimal price = unitPrice == null ? null : new BigDecimal(unitPrice);
        jdbc.update("INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price, subtotal) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", orderId, productId, name, quantity, price,
                price == null ? null : price.multiply(BigDecimal.valueOf(quantity)));
    }

    String status(long id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
    }

    String paymentRef(long id) {
        return jdbc.queryForObject("SELECT payment_ref FROM orders WHERE id = ?", String.class, id);
    }

    long outbox(long id, String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                Long.class, id, eventType);
    }

    long outboxAll(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, id);
    }

    long refunds(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM refunds WHERE order_id = ?", Long.class, id);
    }

    String payload(long id, String eventType) {
        return jdbc.queryForObject("SELECT payload_json::text FROM outbox WHERE aggregate_id = ? AND event_type = ?",
                String.class, id, eventType);
    }

    static String body(long orderId, String ref, String status, String amount) {
        return "{\"order_id\":" + orderId + ",\"payment_ref\":\"" + ref + "\",\"status\":\"" + status
                + "\",\"amount\":\"" + amount + "\"}";
    }
}
