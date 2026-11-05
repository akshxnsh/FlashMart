package com.flashmart.order.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static com.flashmart.order.entity.Violations.assertConstraint;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V8's two CHECKs. Native SQL throughout: production writes both columns with
 * raw conditional SQL, and an entity write could be refused by Hibernate before
 * the database is asked.
 */
@SpringBootTest
@Transactional
class V8GuardsTest {

    private static final String TS = "'2030-01-01T10:00:00Z'::timestamptz";

    @Autowired
    private EntityManager entityManager;

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    private long insertOrder(long buyerId, String status, String reconciled) {
        boolean payable = status.equals("PENDING_PAYMENT") || status.equals("PAID");
        return ((Number) entityManager.createNativeQuery(
                "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at, amount, "
                        + "payment_ref, payment_reconciled_at) values (" + buyerId + ", 7, '" + status + "', 'key-"
                        + buyerId + "', " + TS + ", " + (payable ? "10.00, 'pay_" + buyerId + "'" : "null, null")
                        + ", " + reconciled + ") returning id").getSingleResult()).longValue();
    }

    // --- ck_refunds_processing_has_claimed_at ---

    @Test
    void aProcessingRefundInsertedWithoutClaimedAtIsRejected() {
        long orderId = insertOrder(1, "PAID", "null");

        assertConstraint("ck_refunds_processing_has_claimed_at", () -> execute(
                "insert into refunds (order_id, payment_ref, amount, status) values ("
                        + orderId + ", 'pay_x', 10.00, 'PROCESSING')"));
    }

    /** The path production uses: the worker's claim CAS forgetting to stamp claimed_at. */
    @Test
    void claimingARefundWithoutStampingClaimedAtIsRejected() {
        long orderId = insertOrder(1, "PAID", "null");
        execute("insert into refunds (order_id, payment_ref, amount, status) values ("
                + orderId + ", 'pay_x', 10.00, 'PENDING')");

        assertConstraint("ck_refunds_processing_has_claimed_at", () -> execute(
                "update refunds set status = 'PROCESSING' where payment_ref = 'pay_x'"));
    }

    @Test
    void aProcessingRefundWithClaimedAtIsAccepted() {
        long orderId = insertOrder(1, "PAID", "null");

        execute("insert into refunds (order_id, payment_ref, amount, status, claimed_at) values ("
                + orderId + ", 'pay_x', 10.00, 'PROCESSING', clock_timestamp())");

        assertEquals(1L, ((Number) entityManager.createNativeQuery(
                "select count(*) from refunds where payment_ref = 'pay_x'").getSingleResult()).longValue());
    }

    // --- ck_orders_reconciled_only_when_failed ---

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "PENDING_PAYMENT", "PAID"})
    void aReconciledStampOnANonFailureOrderIsRejected(String status) {
        assertConstraint("ck_orders_reconciled_only_when_failed",
                () -> insertOrder(1, status, TS));
    }

    @Test
    void stampingALiveOrderAsReconciledIsRejected() {
        long orderId = insertOrder(1, "CREATED", "null");

        assertConstraint("ck_orders_reconciled_only_when_failed", () -> execute(
                "update orders set payment_reconciled_at = clock_timestamp() where id = " + orderId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT_FAILED", "PAYMENT_UNAVAILABLE", "ORDER_EXPIRED", "RESERVATION_FAILED"})
    void aReconciledStampOnEveryFailureStateIsAccepted(String status) {
        long orderId = insertOrder(1, status, TS);

        assertEquals(1L, ((Number) entityManager.createNativeQuery(
                "select count(*) from orders where id = " + orderId
                        + " and payment_reconciled_at is not null").getSingleResult()).longValue());
    }
}
