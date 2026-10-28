package com.flashmart.order.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * uk_orders_buyer_sale_live_or_paid: at most one CREATED / PENDING_PAYMENT /
 * PAID order per (buyer, flash sale). Every status change here is native SQL
 * because production moves status with a raw compare-and-set, never through
 * the entity.
 */
@SpringBootTest
@Transactional
class OrderOneLiveOrderPerBuyerSaleTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private EntityManager entityManager;

    private Order newOrder(long buyerId, long flashSaleId, String idempotencyKey, OrderStatus status) {
        Order order = new Order();
        order.setBuyerId(buyerId);
        order.setFlashSaleId(flashSaleId);
        order.setStatus(status);
        order.setIdempotencyKey(idempotencyKey);
        order.setExpiresAt(DEADLINE);
        if (status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.PAID) {
            order.setAmount(new BigDecimal("100.00"));
            order.setPaymentRef("pay_" + idempotencyKey);
        }
        return order;
    }

    private Order persist(Order order) {
        entityManager.persist(order);
        entityManager.flush();
        return order;
    }

    /** What the CAS writes do: a raw UPDATE that Hibernate never sees. */
    private void moveTo(Order order, String status) {
        int rows = entityManager.createNativeQuery(
                        "update orders set status = :s, amount = coalesce(amount, 100.00), "
                                + "payment_ref = coalesce(payment_ref, 'pay_' || idempotency_key) where id = :id")
                .setParameter("s", status)
                .setParameter("id", order.getId())
                .executeUpdate();
        if (rows != 1) {
            throw new AssertionError("expected to move exactly one row, moved " + rows);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PENDING_PAYMENT", "PAID"})
    void aLiveOrPaidOrderBlocksANewCreatedOrder(String status) {
        Order first = persist(newOrder(1L, 7L, "key-1", OrderStatus.CREATED));
        moveTo(first, status);

        Violations.assertConstraint("uk_orders_buyer_sale_live_or_paid",
                () -> persist(newOrder(1L, 7L, "key-2", OrderStatus.CREATED)));
    }

    @Test
    void twoPaidOrdersForTheSameBuyerAndSaleAreRejected() {
        persist(newOrder(1L, 7L, "key-1", OrderStatus.PAID));

        Violations.assertConstraint("uk_orders_buyer_sale_live_or_paid",
                () -> persist(newOrder(1L, 7L, "key-2", OrderStatus.PAID)));
    }

    /** One test per status: the slot is free only if the native UPDATE really frees it. */
    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT_FAILED", "ORDER_EXPIRED", "PAYMENT_UNAVAILABLE", "RESERVATION_FAILED"})
    void anOrderLeavingTheRuleFreesTheSlotForARetry(String failureStatus) {
        Order first = persist(newOrder(1L, 7L, "key-1", OrderStatus.CREATED));
        moveTo(first, failureStatus);

        Order retry = persist(newOrder(1L, 7L, "key-2", OrderStatus.CREATED));

        assertNotNull(retry.getId());
    }

    @Test
    void aDifferentBuyerMayHaveALiveOrderForTheSameSale() {
        persist(newOrder(1L, 7L, "key-1", OrderStatus.CREATED));

        assertNotNull(persist(newOrder(2L, 7L, "key-1", OrderStatus.CREATED)).getId());
    }

    @Test
    void theSameBuyerMayHaveALiveOrderInADifferentSale() {
        persist(newOrder(1L, 7L, "key-1", OrderStatus.CREATED));

        assertNotNull(persist(newOrder(1L, 8L, "key-2", OrderStatus.CREATED)).getId());
    }
}
