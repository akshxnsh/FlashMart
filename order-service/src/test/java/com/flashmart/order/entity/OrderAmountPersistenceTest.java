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
 * architecture.md section 4 asks for this test by name: {@code orders.amount}
 * must equal {@code SUM(order_items.subtotal)}.
 *
 * <p><strong>Nothing in the schema enforces it.</strong> A cross-row sum is
 * not expressible as a row-level {@code CHECK}, and the items are priced one
 * at a time, so the invariant is only true at the moment the last item has
 * been priced and the amount has been set. This test documents the invariant
 * and pins the arithmetic; the guarantee itself has to come from the code that
 * sets the amount, which does not exist yet. Breaking a constraint will not
 * make this test fail, because there is no constraint to break.
 */
@SpringBootTest
@Transactional
class OrderAmountPersistenceTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private EntityManager entityManager;

    private OrderItem pricedItem(Long orderId, long productId, int quantity, String unitPrice) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setProductId(productId);
        item.setProductName("Product " + productId);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setSubtotal(new BigDecimal(unitPrice).multiply(new BigDecimal(quantity)));
        return item;
    }

    @Test
    void amountEqualsTheSumOfItemSubtotals() {
        Order order = new Order();
        order.setBuyerId(1L);
        order.setFlashSaleId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey("key-1");
        order.setExpiresAt(DEADLINE);
        entityManager.persist(order);
        entityManager.flush();

        // 2 x 49.99 = 99.98, plus 1 x 100.00 = 100.00
        entityManager.persist(pricedItem(order.getId(), 100L, 2, "49.99"));
        entityManager.persist(pricedItem(order.getId(), 200L, 1, "100.00"));
        entityManager.flush();

        // What the reserve loop does once the last item is priced: set the
        // amount and make the order payable, in one raw write (production moves
        // status with raw SQL, not through the entity).
        entityManager.createNativeQuery(
                        "update orders set amount = 199.98, status = 'PENDING_PAYMENT', payment_ref = 'pay_1' "
                                + "where id = :id")
                .setParameter("id", order.getId())
                .executeUpdate();

        // Both sides read back from SQL, never from the entity's own field.
        BigDecimal summed = (BigDecimal) entityManager.createNativeQuery(
                        "select sum(subtotal) from order_items where order_id = :id")
                .setParameter("id", order.getId())
                .getSingleResult();
        BigDecimal stored = (BigDecimal) entityManager.createNativeQuery(
                        "select amount from orders where id = :id")
                .setParameter("id", order.getId())
                .getSingleResult();

        assertEquals(0, new BigDecimal("199.98").compareTo(summed),
                "the items do not sum to the expected amount");
        assertEquals(0, stored.compareTo(summed),
                "orders.amount disagrees with SUM(order_items.subtotal)");
    }

    /**
     * The guard that <em>is</em> enforced, shown from the direction that
     * matters: an order cannot become payable while any item is still
     * unpriced, because the amount cannot be known yet.
     */
    @Test
    void anOrderWithAnUnpricedItemHasNoAmountToBecomePayableWith() {
        Order order = new Order();
        order.setBuyerId(1L);
        order.setFlashSaleId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey("key-1");
        order.setExpiresAt(DEADLINE);
        entityManager.persist(order);
        entityManager.flush();

        entityManager.persist(pricedItem(order.getId(), 100L, 2, "49.99"));
        OrderItem unpriced = new OrderItem();
        unpriced.setOrderId(order.getId());
        unpriced.setProductId(200L);
        unpriced.setQuantity(1);
        entityManager.persist(unpriced);
        entityManager.flush();

        Object unpricedCount = entityManager.createNativeQuery(
                        "select count(*) from order_items where order_id = :id and subtotal is null")
                .setParameter("id", order.getId())
                .getSingleResult();
        assertEquals(1L, ((Number) unpricedCount).longValue(),
                "one item is still waiting for its reserve() to return");

        // The amount is still unknown, so the move to payable must be refused.
        // payment_ref is set so that the amount guard is the only one that can fire.
        Violations.assertConstraint("ck_orders_amount_present_when_payable", () -> entityManager
                .createNativeQuery("update orders set status = 'PENDING_PAYMENT', payment_ref = 'pay_1' "
                        + "where id = :id")
                .setParameter("id", order.getId())
                .executeUpdate());
    }
}
