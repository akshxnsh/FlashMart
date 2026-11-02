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
     * holds it as PENDING and saves an allowed field. The claim must survive.
     * {@code amount} is the only column here that is not CAS-owned.
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
        assertEquals(0, new BigDecimal("1.00").compareTo((BigDecimal) entityManager
                        .createNativeQuery("select amount from refunds where id = :id")
                        .setParameter("id", id).getSingleResult()),
                "the allowed field should still be written, or the test proves nothing");
    }

    @Test
    void settingStatusOnAManagedRefundIsIgnoredByTheDatabase() {
        Long id = persistedPendingRefundId();
        Refund managed = entityManager.find(Refund.class, id);

        managed.setStatus(RefundStatus.SUCCEEDED);
        entityManager.flush();

        assertEquals("PENDING", statusInDatabase(id));
    }
}
