package com.flashmart.order.sweep;

import com.flashmart.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One order's expiry, in its own short transaction. A separate bean, called
 * through the Spring proxy, for the same reason as {@code OrderCreationTransaction}:
 * a {@code @Transactional} method called from inside its own class skips the
 * proxy and gets no transaction.
 */
@Component
@RequiredArgsConstructor
public class ExpiryTransaction {

    private final OrderRepository orders;
    private final JdbcTemplate jdbc;

    /**
     * The existing {@code expire} CAS, and {@code OrderCancelled{ORDER_EXPIRED}}
     * only if THIS call won it, in the same transaction. The event exists if and
     * only if the state change committed: a second sweep instance, the webhook
     * or the payment step that won the row first leaves this call with 0 rows
     * and no event.
     *
     * @return whether this call expired the order
     */
    @Transactional
    public boolean expire(long orderId, long buyerId, String expectedStatus) {
        if (orders.expire(orderId, expectedStatus) != 1) {
            return false;
        }
        jdbc.update("INSERT INTO outbox (aggregate_id, event_type, payload_json) VALUES (?, ?, ?::jsonb)",
                orderId, "OrderCancelled",
                "{\"order_id\":" + orderId + ",\"reason\":\"ORDER_EXPIRED\",\"buyer_id\":" + buyerId + "}");
        return true;
    }
}
