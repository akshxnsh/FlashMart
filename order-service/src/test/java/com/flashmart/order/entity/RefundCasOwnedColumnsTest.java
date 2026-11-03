package com.flashmart.order.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards A1 for {@code refunds}: status, attempts, next_retry_at, claimed_at
 * and last_error belong to the worker's raw claim and fenced finishing write.
 * Columns are read back natively, never through the entity.
 */
@SpringBootTest
@Transactional
class RefundCasOwnedColumnsTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private EntityManager entityManager;

    private Long persistedPendingRefundId() {
        Order order = new Order();
        order.setBuyerId(1L);
        order.setFlashSaleId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey("key-1");
        order.setExpiresAt(DEADLINE);
        entityManager.persist(order);

        Refund refund = new Refund();
        refund.setOrderId(order.getId());
        refund.setPaymentRef("pay_abc123");
        refund.setAmount(new BigDecimal("199.98"));
        refund.setStatus(RefundStatus.PENDING);
        entityManager.persist(refund);
        entityManager.flush();
        entityManager.clear();
        return refund.getId();
    }

    private String statusInDatabase(Long id) {
        return (String) entityManager
                .createNativeQuery("select status from refunds where id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    /**
     * The worker claims the refund (PENDING to PROCESSING) while another thread
     * holds it as PENDING and saves a field. The claim must survive. Every
     * column of {@code refunds} is now updatable = false, so the save writes
     * nothing; there is no allowed field left to prove a write happened.
     */
    @Test
    void aStaleSaveOfAnotherFieldDoesNotOverwriteTheClaim() {
        Long id = persistedPendingRefundId();
        Refund managed = entityManager.find(Refund.class, id); // still believes PENDING

        int claimed = entityManager.createNativeQuery(
                        "update refunds set status = 'PROCESSING', claimed_at = clock_timestamp() "
                                + "where id = :id and status = 'PENDING'")
                .setParameter("id", id)
                .executeUpdate();
        assertEquals(1, claimed, "the raw claim should have matched the row");

        managed.setAmount(new BigDecimal("1.00"));
        entityManager.flush();

        assertEquals("PROCESSING", statusInDatabase(id));
    }

    @Test
    void settingStatusOnAManagedRefundIsIgnoredByTheDatabase() {
        Long id = persistedPendingRefundId();
        Refund managed = entityManager.find(Refund.class, id);

        managed.setStatus(RefundStatus.SUCCEEDED);
        entityManager.flush();

        assertEquals("PENDING", statusInDatabase(id));
    }

    private Object columnInDatabase(String column, Long id) {
        return entityManager
                .createNativeQuery("select " + column + " from refunds where id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    /** Set once at insert. A setter on a managed entity must not change them. */
    @Test
    void settingOrderIdOnAManagedRefundIsIgnoredByTheDatabase() {
        Long id = persistedPendingRefundId();
        Refund managed = entityManager.find(Refund.class, id);
        long original = managed.getOrderId();

        managed.setOrderId(original + 1000);
        entityManager.flush();

        assertEquals(original, ((Number) columnInDatabase("order_id", id)).longValue());
    }

    @Test
    void settingPaymentRefOnAManagedRefundIsIgnoredByTheDatabase() {
        Long id = persistedPendingRefundId();
        Refund managed = entityManager.find(Refund.class, id);

        managed.setPaymentRef("pay_other");
        entityManager.flush();

        assertEquals("pay_abc123", columnInDatabase("payment_ref", id));
    }

    @Test
    void settingAmountOnAManagedRefundIsIgnoredByTheDatabase() {
        Long id = persistedPendingRefundId();
        Refund managed = entityManager.find(Refund.class, id);

        managed.setAmount(new BigDecimal("1.00"));
        entityManager.flush();

        assertEquals(0, new BigDecimal("199.98").compareTo((BigDecimal) columnInDatabase("amount", id)));
    }
}
