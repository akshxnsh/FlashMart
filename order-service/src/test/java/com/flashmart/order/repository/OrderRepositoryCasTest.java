package com.flashmart.order.repository;

import com.flashmart.order.entity.Order;
import com.flashmart.order.entity.OrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Winner / loser tests for every CAS in {@link OrderRepository}. Every result
 * is read back with a native query: a managed entity would return its own
 * stale copy, because the CAS bypasses the persistence context.
 */
@SpringBootTest
@Transactional
class OrderRepositoryCasTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private OrderRepository orders;

    @Autowired
    private EntityManager entityManager;

    private long nextBuyer = 1;

    /** Inserts an order in {@code status}, with the columns that status requires. */
    private Long orderIn(OrderStatus status) {
        boolean payable = status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.PAID;
        Order order = new Order();
        order.setBuyerId(nextBuyer++); // distinct buyers: V6 allows one live order per buyer and sale
        order.setFlashSaleId(7L);
        order.setStatus(status);
        order.setIdempotencyKey("key-" + order.getBuyerId());
        order.setExpiresAt(DEADLINE);
        if (payable) {
            order.setAmount(new BigDecimal("100.00"));
            order.setPaymentRef("pay_" + order.getBuyerId());
        }
        orders.saveAndFlush(order);
        return order.getId();
    }

    private String statusOf(Long id) {
        return (String) entityManager.createNativeQuery("select status from orders where id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private BigDecimal amountOf(Long id) {
        return (BigDecimal) entityManager.createNativeQuery("select amount from orders where id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private String paymentRefOf(Long id) {
        return (String) entityManager.createNativeQuery("select payment_ref from orders where id = :id")
                .setParameter("id", id).getSingleResult();
    }

    // ---- setAmount ----

    @Test
    void setAmountWinsOnACreatedOrderWithNoAmount() {
        Long id = orderIn(OrderStatus.CREATED);

        assertEquals(1, orders.setAmount(id, new BigDecimal("199.98")));

        assertEquals(0, new BigDecimal("199.98").compareTo(amountOf(id)));
        assertEquals("CREATED", statusOf(id));
    }

    @Test
    void setAmountLosesOnceTheAmountIsSetAndTheValueIsFrozen() {
        Long id = orderIn(OrderStatus.CREATED);
        orders.setAmount(id, new BigDecimal("199.98"));

        assertEquals(0, orders.setAmount(id, new BigDecimal("5.00")));

        assertEquals(0, new BigDecimal("199.98").compareTo(amountOf(id)));
    }

    @Test
    void setAmountLosesWhenTheOrderIsNotCreated() {
        Long id = orderIn(OrderStatus.PENDING_PAYMENT); // already has amount 100.00

        assertEquals(0, orders.setAmount(id, new BigDecimal("5.00")));

        assertEquals(0, new BigDecimal("100.00").compareTo(amountOf(id)));
        assertEquals("PENDING_PAYMENT", statusOf(id));
    }

    // ---- CREATED -> RESERVATION_FAILED ----

    @Test
    void markReservationFailedWinsFromCreated() {
        Long id = orderIn(OrderStatus.CREATED);

        assertEquals(1, orders.markReservationFailed(id));

        assertEquals("RESERVATION_FAILED", statusOf(id));
    }

    @Test
    void markReservationFailedLosesFromAnyOtherStatus() {
        Long id = orderIn(OrderStatus.PENDING_PAYMENT);

        assertEquals(0, orders.markReservationFailed(id));

        assertEquals("PENDING_PAYMENT", statusOf(id));
    }

    // ---- CREATED -> PAYMENT_UNAVAILABLE ----

    @Test
    void markPaymentUnavailableWinsFromCreated() {
        Long id = orderIn(OrderStatus.CREATED);

        assertEquals(1, orders.markPaymentUnavailable(id));

        assertEquals("PAYMENT_UNAVAILABLE", statusOf(id));
    }

    @Test
    void markPaymentUnavailableLosesFromAnyOtherStatus() {
        Long id = orderIn(OrderStatus.ORDER_EXPIRED); // the sweep got there first

        assertEquals(0, orders.markPaymentUnavailable(id));

        assertEquals("ORDER_EXPIRED", statusOf(id));
    }

    // ---- expire (CREATED or PENDING_PAYMENT -> ORDER_EXPIRED) ----

    @Test
    void expireWinsFromCreated() {
        Long id = orderIn(OrderStatus.CREATED);

        assertEquals(1, orders.expire(id, "CREATED"));

        assertEquals("ORDER_EXPIRED", statusOf(id));
    }

    @Test
    void expireWinsFromPendingPayment() {
        Long id = orderIn(OrderStatus.PENDING_PAYMENT);

        assertEquals(1, orders.expire(id, "PENDING_PAYMENT"));

        assertEquals("ORDER_EXPIRED", statusOf(id));
    }

    /** The sweep read PENDING_PAYMENT, then the webhook paid the order. The sweep must lose. */
    @Test
    void expireLosesWhenTheOrderHasMovedOnToPaid() {
        Long id = orderIn(OrderStatus.PAID);

        assertEquals(0, orders.expire(id, "PENDING_PAYMENT"));

        assertEquals("PAID", statusOf(id));
    }

    @Test
    void expireLosesWhenTheExpectedStatusIsNotTheCurrentOne() {
        Long id = orderIn(OrderStatus.CREATED);

        assertEquals(0, orders.expire(id, "PENDING_PAYMENT"));

        assertEquals("CREATED", statusOf(id));
    }

    /** Even a caller that passes the matching status cannot expire a terminal order. */
    @Test
    void expireCannotExpireAPaidOrderEvenWhenToldItIsPaid() {
        Long id = orderIn(OrderStatus.PAID);

        assertEquals(0, orders.expire(id, "PAID"));

        assertEquals("PAID", statusOf(id));
    }

    // ---- markPaid ----

    @Test
    void markPaidWinsForTheReferenceTheOrderHolds() {
        Long id = orderIn(OrderStatus.PENDING_PAYMENT);

        assertEquals(1, orders.markPaid(id, paymentRefOf(id)));

        assertEquals("PAID", statusOf(id));
    }

    @Test
    void markPaidLosesForADifferentPaymentRef() {
        Long id = orderIn(OrderStatus.PENDING_PAYMENT);

        assertEquals(0, orders.markPaid(id, "pay_some_other_payment"));

        assertEquals("PENDING_PAYMENT", statusOf(id));
    }

    @Test
    void markPaidLosesWhenTheOrderIsNotPendingPayment() {
        Long id = orderIn(OrderStatus.ORDER_EXPIRED);

        assertEquals(0, orders.markPaid(id, "pay_anything"));

        assertEquals("ORDER_EXPIRED", statusOf(id));
    }

    // ---- markPaymentFailed ----

    @Test
    void markPaymentFailedWinsFromPendingPayment() {
        Long id = orderIn(OrderStatus.PENDING_PAYMENT);

        assertEquals(1, orders.markPaymentFailed(id));

        assertEquals("PAYMENT_FAILED", statusOf(id));
    }

    /** A late FAILED webhook after the order was paid must change nothing. */
    @Test
    void markPaymentFailedLosesOnAPaidOrder() {
        Long id = orderIn(OrderStatus.PAID);

        assertEquals(0, orders.markPaymentFailed(id));

        assertEquals("PAID", statusOf(id));
    }

    // ---- finders ----

    @Test
    void findByBuyerIdAndIdempotencyKeyFindsTheOrder() {
        Long id = orderIn(OrderStatus.CREATED);
        Order order = orders.findById(id).orElseThrow();

        Order found = orders.findByBuyerIdAndIdempotencyKey(order.getBuyerId(), order.getIdempotencyKey())
                .orElseThrow();

        assertEquals(id, found.getId());
        assertTrue(orders.findByBuyerIdAndIdempotencyKey(order.getBuyerId(), "nope").isEmpty());
    }
}
