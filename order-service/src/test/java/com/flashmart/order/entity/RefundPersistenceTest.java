package com.flashmart.order.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class RefundPersistenceTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private EntityManager entityManager;

    private Long persistedOrderId;

    private Long orderId() {
        if (persistedOrderId == null) {
            Order order = new Order();
            order.setBuyerId(1L);
            order.setFlashSaleId(7L);
            order.setStatus(OrderStatus.CREATED);
            order.setIdempotencyKey("key-1");
            order.setExpiresAt(DEADLINE);
            entityManager.persist(order);
            entityManager.flush();
            persistedOrderId = order.getId();
        }
        return persistedOrderId;
    }

    /** A refund as the webhook handler first writes it: queued, never tried. */
    private Refund newRefund(String paymentRef, String amount) {
        Refund refund = new Refund();
        refund.setOrderId(orderId());
        refund.setPaymentRef(paymentRef);
        refund.setAmount(new BigDecimal(amount));
        refund.setStatus(RefundStatus.PENDING);
        return refund;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        Refund refund = newRefund("pay_abc123", "199.98");
        entityManager.persist(refund);
        entityManager.flush();
        entityManager.clear();

        Refund loaded = entityManager.find(Refund.class, refund.getId());

        assertNotNull(loaded);
        assertEquals(persistedOrderId, loaded.getOrderId());
        assertEquals("pay_abc123", loaded.getPaymentRef());
        assertEquals(0, new BigDecimal("199.98").compareTo(loaded.getAmount()));
        assertEquals(RefundStatus.PENDING, loaded.getStatus());
        assertEquals(0, loaded.getAttempts());
        assertNull(loaded.getLastError());
    }

    @Test
    void statusIsStoredAsTextNotAsANumber() {
        Refund refund = newRefund("pay_abc123", "199.98");
        entityManager.persist(refund);
        entityManager.flush();

        Object raw = entityManager
                .createNativeQuery("select status from refunds where id = :id")
                .setParameter("id", refund.getId())
                .getSingleResult();

        assertEquals("PENDING", raw);
    }

    /**
     * A new row is unclaimed and unscheduled. Both columns are written later
     * by the claim SQL using the database clock, never by Java and never by a
     * column DEFAULT, because the worker compares them against the database's
     * own now().
     */
    @Test
    void aNewRefundIsUnclaimedAndUnscheduled() {
        Refund refund = newRefund("pay_abc123", "199.98");

        entityManager.persist(refund);
        entityManager.flush();

        assertNull(refund.getClaimedAt(), "nothing has claimed this refund");
        assertNull(refund.getNextRetryAt(), "nothing has scheduled a retry");
    }

    @Test
    void databaseRejectsAnUnknownStatus() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into refunds (order_id, payment_ref, amount, status) "
                                + "values (:orderId, 'pay_x', 10.00, 'REFUNDED')")
                .setParameter("orderId", orderId())
                .executeUpdate());
    }

    /**
     * Refunds are per payment, not per order: an order may hold several
     * captured payments, but each is refunded once. This unique key is the
     * idempotency guard for the whole refund path, so a redelivered webhook
     * loses here instead of refunding twice.
     */
    @Test
    void twoRefundsCannotShareAPaymentRef() {
        entityManager.persist(newRefund("pay_abc123", "199.98"));
        entityManager.flush();

        Refund redelivery = newRefund("pay_abc123", "199.98");

        Violations.assertConstraint("uk_refunds_payment_ref", () -> {
            entityManager.persist(redelivery);
            entityManager.flush();
        });
    }

    /** One order, two captured payments, each refunded separately. */
    @Test
    void oneOrderMayHaveSeveralRefundsForDifferentPayments() {
        entityManager.persist(newRefund("pay_abc123", "199.98"));
        entityManager.persist(newRefund("pay_def456", "199.98"));
        entityManager.flush();
    }

    /** The boundary: a zero-amount refund is a row a worker would still call for. */
    @Test
    void aZeroAmountIsRejected() {
        Refund refund = newRefund("pay_abc123", "0.00");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(refund);
            entityManager.flush();
        });
    }

    @Test
    void aNegativeAmountIsRejected() {
        Refund refund = newRefund("pay_abc123", "-10.00");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(refund);
            entityManager.flush();
        });
    }

    @Test
    void aNegativeAttemptsCountIsRejected() {
        Refund refund = newRefund("pay_abc123", "199.98");
        refund.setAttempts(-1);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(refund);
            entityManager.flush();
        });
    }

    @Test
    void aRefundMustBelongToAnExistingOrder() {
        Refund orphan = new Refund();
        orphan.setOrderId(999_999L);
        orphan.setPaymentRef("pay_abc123");
        orphan.setAmount(new BigDecimal("199.98"));
        orphan.setStatus(RefundStatus.PENDING);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(orphan);
            entityManager.flush();
        });
    }

    /** As in Order: a re-read cannot see a field left null on the instance. */
    @Test
    void createdAtAndUpdatedAtAreReadBackOntoTheInstanceThatWasPersisted() {
        Refund refund = newRefund("pay_abc123", "199.98");

        entityManager.persist(refund);
        entityManager.flush();

        assertNotNull(refund.getCreatedAt(),
                "created_at was not read back after insert, is @Generated missing?");
        assertNotNull(refund.getUpdatedAt(),
                "updated_at was not read back after insert, is @Generated missing?");
    }

    /**
     * The claim is raw conditional SQL, exactly like this. A Java-stamped
     * updated_at would never move for a refund, because no code path touches
     * the row through Hibernate.
     */
    @Test
    void updatedAtMovesWhenTheRowIsClaimedByRawSql() {
        Refund refund = newRefund("pay_abc123", "199.98");
        entityManager.persist(refund);
        entityManager.flush();
        Instant beforeClaim = refund.getUpdatedAt();

        // The shape of the real claim: a CAS on status that also stamps the
        // lease from the database clock.
        int claimed = entityManager.createNativeQuery(
                        "update refunds set status = 'PROCESSING', claimed_at = clock_timestamp() "
                                + "where id = :id and status = 'PENDING'")
                .setParameter("id", refund.getId())
                .executeUpdate();
        entityManager.refresh(refund);

        assertEquals(1, claimed, "the claim should have matched exactly one row");
        assertNotNull(refund.getClaimedAt(), "the claim did not stamp the lease");
        assertTrue(refund.getUpdatedAt().isAfter(beforeClaim),
                "the trigger did not bump updated_at on a raw SQL claim");
    }
}
