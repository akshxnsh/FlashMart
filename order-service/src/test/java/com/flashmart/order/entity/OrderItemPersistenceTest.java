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

@SpringBootTest
@Transactional
class OrderItemPersistenceTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private EntityManager entityManager;

    private Long persistedOrderId;

    private Long persistOrder(long buyerId, String idempotencyKey) {
        Order order = new Order();
        order.setBuyerId(buyerId);
        order.setFlashSaleId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey(idempotencyKey);
        order.setExpiresAt(DEADLINE);
        entityManager.persist(order);
        entityManager.flush();
        return order.getId();
    }

    /** An item as it is first written: inserted up front, not yet priced. */
    private OrderItem newUnpricedItem(long productId, int quantity) {
        if (persistedOrderId == null) {
            persistedOrderId = persistOrder(1L, "key-1");
        }
        OrderItem item = new OrderItem();
        item.setOrderId(persistedOrderId);
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    /** The same item once that product's reserve() has returned. */
    private OrderItem newPricedItem(long productId, int quantity, String unitPrice) {
        OrderItem item = newUnpricedItem(productId, quantity);
        item.setProductName("Puma Running Shoe");
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setSubtotal(new BigDecimal(unitPrice).multiply(new BigDecimal(quantity)));
        return item;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        OrderItem item = newPricedItem(100L, 2, "49.99");
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        OrderItem loaded = entityManager.find(OrderItem.class, item.getId());

        assertNotNull(loaded);
        assertEquals(persistedOrderId, loaded.getOrderId());
        assertEquals(100L, loaded.getProductId());
        assertEquals("Puma Running Shoe", loaded.getProductName());
        assertEquals(2, loaded.getQuantity());
        assertEquals(0, new BigDecimal("49.99").compareTo(loaded.getUnitPrice()));
        assertEquals(0, new BigDecimal("99.98").compareTo(loaded.getSubtotal()));
    }

    /**
     * The state every item is created in. If this ever fails, orders can no
     * longer be written before their items are priced, which is the whole
     * shape of the reserve loop.
     */
    @Test
    void anUnpricedItemIsAllowed() {
        OrderItem item = newUnpricedItem(100L, 2);

        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        OrderItem loaded = entityManager.find(OrderItem.class, item.getId());
        assertNull(loaded.getProductName());
        assertNull(loaded.getUnitPrice());
        assertNull(loaded.getSubtotal());
    }

    /**
     * Half-filled pricing would make the row look priced to one query and
     * unpriced to another, and orders.amount would then be summed from a
     * subtotal whose unit_price nobody can account for.
     */
    @Test
    void anItemWithANameButNoPriceIsRejected() {
        OrderItem item = newUnpricedItem(100L, 2);
        item.setProductName("Puma Running Shoe");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    /** The same guard from the other direction. */
    @Test
    void anItemWithAPriceButNoNameIsRejected() {
        OrderItem item = newUnpricedItem(100L, 2);
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    /** The boundary: a `quantity >= 0` guard would let this through. */
    @Test
    void aZeroQuantityIsRejected() {
        OrderItem item = newUnpricedItem(100L, 0);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    @Test
    void aNegativeQuantityIsRejected() {
        OrderItem item = newUnpricedItem(100L, -1);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    /**
     * subtotal is stored rather than computed, so nothing but the CHECK stops
     * it disagreeing with its own inputs. The sum of these is what the buyer
     * is charged.
     */
    @Test
    void aSubtotalThatIsNotQuantityTimesUnitPriceIsRejected() {
        OrderItem item = newUnpricedItem(100L, 2);
        item.setProductName("Puma Running Shoe");
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("49.99"));

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    @Test
    void aNegativeUnitPriceIsRejected() {
        OrderItem item = newUnpricedItem(100L, 1);
        item.setProductName("Puma Running Shoe");
        item.setUnitPrice(new BigDecimal("-49.99"));
        item.setSubtotal(new BigDecimal("-49.99"));

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    /**
     * NOT NULL cannot stop a blank, and this column is the receipt: it is
     * snapshotted at reservation time and never resynced, so a blank here
     * survives into a permanent record of a completed purchase. Carried
     * forward from Inventory's ck_products_name_present.
     */
    @Test
    void aBlankProductNameIsRejected() {
        OrderItem item = newUnpricedItem(100L, 2);
        item.setProductName("   ");
        item.setUnitPrice(new BigDecimal("49.99"));
        item.setSubtotal(new BigDecimal("99.98"));

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    /**
     * One line per product. A retried add would otherwise double the quantity
     * silently, and the order would be charged twice for one item.
     */
    @Test
    void theSameProductCannotAppearTwiceInOneOrder() {
        entityManager.persist(newUnpricedItem(100L, 2));
        entityManager.flush();

        OrderItem duplicate = newUnpricedItem(100L, 2);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        });
    }

    /** The unique key is scoped to the order, not global to the product. */
    @Test
    void theSameProductMayAppearInDifferentOrders() {
        OrderItem first = newUnpricedItem(100L, 2);
        entityManager.persist(first);

        OrderItem second = new OrderItem();
        // a different buyer: one buyer cannot hold two live orders for a sale
        second.setOrderId(persistOrder(2L, "key-2"));
        second.setProductId(100L);
        second.setQuantity(2);
        entityManager.persist(second);

        entityManager.flush();
    }

    /** Same-service relation, so this one really is a foreign key. */
    @Test
    void anItemMustBelongToAnExistingOrder() {
        OrderItem orphan = new OrderItem();
        orphan.setOrderId(999_999L);
        orphan.setProductId(100L);
        orphan.setQuantity(1);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(orphan);
            entityManager.flush();
        });
    }
}
