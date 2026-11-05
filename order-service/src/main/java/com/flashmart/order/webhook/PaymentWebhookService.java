package com.flashmart.order.webhook;

import com.flashmart.order.entity.OrderStatus;
import com.flashmart.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Applies one verified payment webhook to its order, in ONE local transaction
 * (architecture.md section 6; decisions.md, "Day 6"). Every status write is a
 * CAS from {@link OrderRepository} whose rows-affected decides what else is
 * written: the outbox row exists if and only if this caller's CAS won, and a
 * refund row is written only after the CAS lost.
 *
 * <p>The order is loaded by the id the webhook carries, never by
 * {@code payment_ref}: the ref is NULL in exactly the crash window a webhook is
 * most likely to arrive in.
 *
 * <p>Reads and inserts are native SQL through {@link JdbcTemplate}, which joins
 * the JPA transaction and bypasses the persistence context, so no stale entity
 * can be flushed over a CAS. {@code orders} has no {@code version} column (the
 * CAS is value-based), so there is nothing to bump.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    private final OrderRepository orders;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private record OrderRow(OrderStatus status, String paymentRef, long buyerId, BigDecimal amount) {
    }

    @Transactional
    public WebhookOutcome apply(PaymentWebhook webhook) {
        Optional<OrderRow> order = read(webhook.orderId());
        if (order.isEmpty()) {
            log.warn("payment webhook for unknown order {} (ref {}, {})",
                    webhook.orderId(), webhook.paymentRef(), webhook.status());
            return WebhookOutcome.ORDER_NOT_FOUND;
        }
        // B4: Order's own PENDING_PAYMENT CAS has not committed yet. Change
        // nothing; the gateway retries. Both SUCCESS and FAILED.
        if (order.get().status() == OrderStatus.CREATED) {
            return WebhookOutcome.TOO_EARLY;
        }
        return webhook.status() == PaymentWebhook.Status.SUCCESS
                ? success(webhook)
                : failed(webhook, order.get().buyerId());
    }

    private WebhookOutcome success(PaymentWebhook webhook) {
        long id = webhook.orderId();
        if (orders.markPaid(id, webhook.paymentRef(), webhook.amount()) == 1) {
            OrderRow paid = read(id).orElseThrow();
            outbox(id, "PaymentConfirmed", paymentConfirmed(id, paid));
            return WebhookOutcome.PAID;
        }
        // Lost: re-read, because the first read may be stale (the sweep, or
        // another delivery of this webhook, may have committed since).
        OrderRow now = read(id).orElseThrow();
        if (now.status() == OrderStatus.PAID && webhook.paymentRef().equals(now.paymentRef())) {
            return WebhookOutcome.ALREADY_PAID;
        }
        String why = describeLoss(webhook, now);
        // Ref and amount from the WEBHOOK, never from the order: the order's
        // ref may be NULL after a crash, and we refund what was captured.
        int inserted = jdbc.update("INSERT INTO refunds (order_id, payment_ref, amount, status) "
                        + "VALUES (?, ?, ?, 'PENDING') ON CONFLICT (payment_ref) DO NOTHING",
                id, webhook.paymentRef(), webhook.amount());
        log.warn("order {}: refund {} for payment {} amount {} ({})", id,
                inserted == 1 ? "recorded" : "already recorded", webhook.paymentRef(),
                webhook.amount().toPlainString(), why);
        return WebhookOutcome.REFUND_RECORDED;
    }

    private static String describeLoss(PaymentWebhook webhook, OrderRow now) {
        if (now.status() == OrderStatus.PENDING_PAYMENT && webhook.paymentRef().equals(now.paymentRef())) {
            return "AMOUNT MISMATCH: order amount " + now.amount().toPlainString()
                    + ", webhook amount " + webhook.amount().toPlainString() + "; order stays PENDING_PAYMENT";
        }
        return "order is " + now.status() + " holding ref " + now.paymentRef();
    }

    private WebhookOutcome failed(PaymentWebhook webhook, long buyerId) {
        long id = webhook.orderId();
        if (orders.markPaymentFailed(id, webhook.paymentRef()) != 1) {
            return WebhookOutcome.IGNORED;
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("order_id", id);
        payload.put("reason", OrderStatus.PAYMENT_FAILED.name());
        payload.put("buyer_id", buyerId);
        outbox(id, "OrderCancelled", payload);
        return WebhookOutcome.PAYMENT_FAILED;
    }

    /**
     * {@code {order_id, buyer_id, items[], amount}}, flat, money as strings, no
     * {@code brand_id} (decisions.md, "Day 6", point 5). Read inside the same
     * transaction as the winning CAS, so it describes the order that was paid.
     */
    private ObjectNode paymentConfirmed(long id, OrderRow paid) {
        ObjectNode payload = json.createObjectNode();
        payload.put("order_id", id);
        payload.put("buyer_id", paid.buyerId());
        ArrayNode items = payload.putArray("items");
        jdbc.query("SELECT product_id, product_name, quantity, unit_price, subtotal FROM order_items "
                + "WHERE order_id = ? ORDER BY product_id", rs -> {
                    ObjectNode item = items.addObject();
                    item.put("product_id", rs.getLong("product_id"));
                    item.put("product_name", rs.getString("product_name"));
                    item.put("quantity", rs.getInt("quantity"));
                    item.put("unit_price", rs.getBigDecimal("unit_price").toPlainString());
                    item.put("subtotal", rs.getBigDecimal("subtotal").toPlainString());
                }, id);
        payload.put("amount", paid.amount().toPlainString());
        return payload;
    }

    private void outbox(long orderId, String eventType, ObjectNode payload) {
        jdbc.update("INSERT INTO outbox (aggregate_id, event_type, payload_json) VALUES (?, ?, ?::jsonb)",
                orderId, eventType, json.writeValueAsString(payload));
    }

    private Optional<OrderRow> read(long id) {
        return jdbc.query("SELECT status, payment_ref, buyer_id, amount FROM orders WHERE id = ?",
                (rs, n) -> new OrderRow(OrderStatus.valueOf(rs.getString("status")), rs.getString("payment_ref"),
                        rs.getLong("buyer_id"), rs.getBigDecimal("amount")), id).stream().findFirst();
    }
}
