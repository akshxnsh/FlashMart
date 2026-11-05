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
import java.util.List;

import static com.flashmart.order.entity.Violations.assertConstraint;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V7 guards, plus the NOT NULLs and index shapes no earlier test pinned. Every
 * write that Hibernate could reject first goes through native SQL, so the
 * question reaches Postgres. Production moves status with raw SQL as well.
 */
@SpringBootTest
@Transactional
class OrderSchemaHardeningTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");
    private static final String TS = "'2030-01-01T10:00:00Z'::timestamptz";

    @Autowired
    private EntityManager entityManager;

    private Order persistOrder(long buyerId, String idempotencyKey) {
        Order order = new Order();
        order.setBuyerId(buyerId);
        order.setFlashSaleId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey(idempotencyKey);
        order.setExpiresAt(DEADLINE);
        entityManager.persist(order);
        entityManager.flush();
        return order;
    }

    private void execute(String sql) {
        entityManager.createNativeQuery(sql).executeUpdate();
    }

    private Object scalar(String sql) {
        return entityManager.createNativeQuery(sql).getSingleResult();
    }

    // --- ck_orders_payment_ref_present_when_payable ---

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "PAID"})
    void aPayableOrderInsertedWithoutAPaymentRefIsRejected(String status) {
        assertConstraint("ck_orders_payment_ref_present_when_payable", () -> execute(
                "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, amount, expires_at) "
                        + "values (1, 7, '" + status + "', 'key-x', 10.00, " + TS + ")"));
    }

    /** The path production uses: a CAS that moves status and forgets the ref. */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING_PAYMENT", "PAID"})
    void movingAnOrderToAPayableStatusWithoutAPaymentRefIsRejected(String status) {
        Order order = persistOrder(1L, "key-1");

        assertConstraint("ck_orders_payment_ref_present_when_payable", () -> execute(
                "update orders set status = '" + status + "', amount = 10.00 where id = " + order.getId()));
    }

    @Test
    void movingAnOrderToPendingPaymentWithAPaymentRefIsAccepted() {
        Order order = persistOrder(1L, "key-1");

        execute("update orders set status = 'PENDING_PAYMENT', amount = 10.00, payment_ref = 'pay_1' "
                + "where id = " + order.getId());

        assertEquals("PENDING_PAYMENT", scalar("select status from orders where id = " + order.getId()));
    }

    // --- non-blank guards ---

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankIdempotencyKeyIsRejected(String blank) {
        assertConstraint("ck_orders_idempotency_key_present", () -> execute(
                "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at) "
                        + "values (1, 7, 'CREATED', '" + blank + "', " + TS + ")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankOrderPaymentRefIsRejected(String blank) {
        assertConstraint("ck_orders_payment_ref_present", () -> execute(
                "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, payment_ref, expires_at) "
                        + "values (1, 7, 'CREATED', 'key-x', '" + blank + "', " + TS + ")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankRefundPaymentRefIsRejected(String blank) {
        Order order = persistOrder(1L, "key-1");

        assertConstraint("ck_refunds_payment_ref_present", () -> execute(
                "insert into refunds (order_id, payment_ref, amount, status) values ("
                        + order.getId() + ", '" + blank + "', 10.00, 'PENDING')"));
    }

    /** NULL stays allowed on orders.payment_ref: it is NULL until initiate is saved. */
    @Test
    void aNullOrderPaymentRefIsStillAccepted() {
        execute("insert into orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at) "
                + "values (1, 7, 'CREATED', 'key-x', " + TS + ")");

        assertEquals(1L, ((Number) scalar("select count(*) from orders where payment_ref is null")).longValue());
    }

    // --- idempotency key outlives the first order's failure ---

    /**
     * Pins behaviour: a failed order does not free its idempotency key. The
     * live-or-paid index lets the buyer try again, but only under a NEW key.
     */
    @Test
    void theSameIdempotencyKeyAfterTheFirstOrderFailedIsStillRejected() {
        Order first = persistOrder(1L, "key-1");
        execute("update orders set status = 'RESERVATION_FAILED' where id = " + first.getId());

        Order retry = new Order();
        retry.setBuyerId(1L);
        retry.setFlashSaleId(7L);
        retry.setStatus(OrderStatus.CREATED);
        retry.setIdempotencyKey("key-1");
        retry.setExpiresAt(DEADLINE);

        assertConstraint("uk_orders_buyer_id_idempotency_key", () -> {
            entityManager.persist(retry);
            entityManager.flush();
        });
    }

    // --- widened money columns ---

    @Test
    void theMoneyColumnsAreWidenedExceptUnitPrice() {
        assertEquals(14, precision("orders", "amount"));
        assertEquals(14, precision("order_items", "subtotal"));
        assertEquals(14, precision("refunds", "amount"));
        assertEquals(12, precision("order_items", "unit_price"));
    }

    private int precision(String table, String column) {
        return ((Number) scalar("select numeric_precision from information_schema.columns "
                + "where table_name = '" + table + "' and column_name = '" + column + "'")).intValue();
    }

    /** A subtotal and an amount past unit_price's old 12,2 range, with their CHECKs intact. */
    @Test
    void aSubtotalAndAnAmountAboveTheOldRangeAreAccepted() {
        Order order = persistOrder(1L, "key-1");

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(100L);
        item.setProductName("Puma Running Shoe");
        item.setQuantity(5);
        item.setUnitPrice(new BigDecimal("9999999999.99"));
        item.setSubtotal(new BigDecimal("49999999999.95"));
        entityManager.persist(item);
        entityManager.flush();

        execute("update orders set amount = 49999999999.95 where id = " + order.getId());
        execute("insert into refunds (order_id, payment_ref, amount, status) values ("
                + order.getId() + ", 'pay_big', 49999999999.95, 'PENDING')");

        assertEquals(0, new BigDecimal("49999999999.95").compareTo(
                (BigDecimal) scalar("select subtotal from order_items where id = " + item.getId())));
    }

    // --- NOT NULL, put to Postgres directly ---

    @ParameterizedTest
    @ValueSource(strings = {"status", "buyer_id", "flash_sale_id", "idempotency_key"})
    void ordersRejectANullIn(String column) {
        String buyerId = column.equals("buyer_id") ? "null" : "1";
        String flashSaleId = column.equals("flash_sale_id") ? "null" : "7";
        String status = column.equals("status") ? "null" : "'CREATED'";
        String key = column.equals("idempotency_key") ? "null" : "'key-x'";

        Violations.assertNotNull(column, () -> execute(
                "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at) values ("
                        + buyerId + ", " + flashSaleId + ", " + status + ", " + key + ", " + TS + ")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment_ref", "amount", "status"})
    void refundsRejectANullIn(String column) {
        Order order = persistOrder(1L, "key-1");
        String paymentRef = column.equals("payment_ref") ? "null" : "'pay_1'";
        String amount = column.equals("amount") ? "null" : "10.00";
        String status = column.equals("status") ? "null" : "'PENDING'";

        Violations.assertNotNull(column, () -> execute(
                "insert into refunds (order_id, payment_ref, amount, status) values ("
                        + order.getId() + ", " + paymentRef + ", " + amount + ", " + status + ")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aggregate_id", "event_type", "payload_json"})
    void outboxRejectsANullIn(String column) {
        String aggregateId = column.equals("aggregate_id") ? "null" : "12";
        String eventType = column.equals("event_type") ? "null" : "'PaymentConfirmed'";
        String payload = column.equals("payload_json") ? "null" : "'{}'::jsonb";

        Violations.assertNotNull(column, () -> execute(
                "insert into outbox (aggregate_id, event_type, payload_json) values ("
                        + aggregateId + ", " + eventType + ", " + payload + ")"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"order_id", "quantity"})
    void orderItemsRejectANullIn(String column) {
        Order order = persistOrder(1L, "key-1");
        String orderId = column.equals("order_id") ? "null" : String.valueOf(order.getId());
        String quantity = column.equals("quantity") ? "null" : "1";

        Violations.assertNotNull(column, () -> execute(
                "insert into order_items (order_id, product_id, quantity) values ("
                        + orderId + ", 100, " + quantity + ")"));
    }

    // --- index structure: the predicate is visible even though behaviour hides it ---

    private String indexDefinition(String name) {
        Object def = scalar("select indexdef from pg_indexes where indexname = '" + name + "'");
        assertNotNull(def, name + " does not exist");
        return (String) def;
    }

    @Test
    void paymentRefIndexIsUniqueAndPartialOnNotNull() {
        String def = indexDefinition("uk_orders_payment_ref");

        assertTrue(def.contains("CREATE UNIQUE INDEX"), def);
        assertTrue(def.contains("(payment_ref)"), def);
        assertTrue(def.contains("WHERE (payment_ref IS NOT NULL)"), def);
    }

    @Test
    void liveOrPaidIndexIsUniqueAndPartialOnExactlyThreeStatuses() {
        String def = indexDefinition("uk_orders_buyer_sale_live_or_paid");

        assertTrue(def.contains("CREATE UNIQUE INDEX"), def);
        assertTrue(def.contains("(buyer_id, flash_sale_id)"), def);
        for (String live : List.of("'CREATED'", "'PENDING_PAYMENT'", "'PAID'")) {
            assertTrue(def.contains(live), def);
        }
        for (String outside : List.of("PAYMENT_FAILED", "ORDER_EXPIRED", "PAYMENT_UNAVAILABLE",
                "RESERVATION_FAILED")) {
            assertFalse(def.contains(outside), def);
        }
    }

    // V9: the sweep's index covers live orders only, read from pg_index's own
    // predicate (not just the indexdef text), and V2's index is gone.
    @Test
    void expirySweepIndexIsPartialOnTheTwoLiveStatusesAndTheOldOneIsGone() {
        String def = indexDefinition("idx_orders_expiry_sweep_live");
        assertFalse(def.contains("UNIQUE"), def);
        assertTrue(def.contains("(expires_at)"), def);
        for (String live : List.of("'CREATED'", "'PENDING_PAYMENT'")) {
            assertTrue(def.contains(live), def);
        }
        for (String outside : List.of("PAID", "PAYMENT_FAILED", "ORDER_EXPIRED", "PAYMENT_UNAVAILABLE",
                "RESERVATION_FAILED")) {
            assertFalse(def.replace("PENDING_PAYMENT", "").contains(outside), def);
        }
        Object predicate = scalar("select pg_get_expr(i.indpred, i.indrelid) from pg_index i "
                + "join pg_class c on c.oid = i.indexrelid where c.relname = 'idx_orders_expiry_sweep_live'");
        assertNotNull(predicate, "the index has no predicate, so it is not partial");

        assertEquals(0L, ((Number) scalar(
                "select count(*) from pg_indexes where indexname = 'idx_orders_expiry_sweep'")).longValue(),
                "V2's (status, expires_at) index must be dropped");
        assertNotNull(scalar("select indexdef from pg_indexes where indexname = 'idx_orders_reconciliation_sweep'"),
                "the reconciliation index is untouched");
    }

    @Test
    void outboxUnpublishedIndexIsPartialOnPublishedAtNull() {
        String def = indexDefinition("idx_outbox_unpublished");

        assertTrue(def.contains("(created_at)"), def);
        assertTrue(def.contains("WHERE (published_at IS NULL)"), def);
    }
}
