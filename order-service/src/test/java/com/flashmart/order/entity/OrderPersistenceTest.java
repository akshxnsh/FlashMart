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
class OrderPersistenceTest {

    /**
     * An absolute fixture, deliberately not {@code now() + something}: the
     * real deadline durations are still open, and a test must not be where one
     * gets picked.
     */
    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private EntityManager entityManager;

    private Order newOrder(long buyerId, String idempotencyKey) {
        Order order = new Order();
        order.setBuyerId(buyerId);
        order.setFlashSaleId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey(idempotencyKey);
        order.setExpiresAt(DEADLINE);
        return order;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        Order order = newOrder(1L, "key-1");
        order.setAmount(new BigDecimal("199.98"));
        order.setPaymentRef("pay_abc123");
        order.setFailureDetail("none yet");
        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        Order loaded = entityManager.find(Order.class, order.getId());

        assertNotNull(loaded);
        assertEquals(1L, loaded.getBuyerId());
        assertEquals(7L, loaded.getFlashSaleId());
        assertEquals(0, new BigDecimal("199.98").compareTo(loaded.getAmount()));
        assertEquals(OrderStatus.CREATED, loaded.getStatus());
        assertEquals("key-1", loaded.getIdempotencyKey());
        assertEquals("pay_abc123", loaded.getPaymentRef());
        assertEquals(DEADLINE, loaded.getExpiresAt());
        assertEquals("none yet", loaded.getFailureDetail());
        assertNull(loaded.getPaymentReconciledAt(), "nothing has reconciled this order");
    }

    @Test
    void statusIsStoredAsTextNotAsANumber() {
        Order order = newOrder(1L, "key-1");
        entityManager.persist(order);
        entityManager.flush();

        Object raw = entityManager
                .createNativeQuery("select status from orders where id = :id")
                .setParameter("id", order.getId())
                .getSingleResult();

        assertEquals("CREATED", raw);
    }

    /**
     * The enum cannot express a bad status, so this has to go in as raw SQL.
     * Without the CHECK the row would store a status no CAS ever matches,
     * stranding the order where nothing can move it out, and silently.
     */
    @Test
    void databaseRejectsAnUnknownStatus() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at) "
                                + "values (1, 7, 'SHIPPED', 'key-x', '2030-01-01T10:00:00Z'::timestamptz)")
                .executeUpdate());
    }

    @Test
    void databaseRejectsAStatusThatDiffersOnlyInCase() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at) "
                                + "values (1, 7, 'created', 'key-x', '2030-01-01T10:00:00Z'::timestamptz)")
                .executeUpdate());
    }

    /** An order may not become payable before it knows what it costs. */
    @Test
    void aPendingPaymentOrderWithoutAnAmountIsRejected() {
        Order order = newOrder(1L, "key-1");
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setAmount(null);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(order);
            entityManager.flush();
        });
    }

    @Test
    void aPaidOrderWithoutAnAmountIsRejected() {
        Order order = newOrder(1L, "key-1");
        order.setStatus(OrderStatus.PAID);
        order.setAmount(null);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(order);
            entityManager.flush();
        });
    }

    /**
     * The other side of the same guard: it is conditional on status, not a
     * NOT NULL. Items are priced one at a time, so a CREATED order has no
     * amount yet for most of its life.
     */
    @Test
    void aCreatedOrderMayHaveNoAmount() {
        Order order = newOrder(1L, "key-1");
        order.setAmount(null);

        entityManager.persist(order);
        entityManager.flush();

        assertNotNull(order.getId());
    }

    @Test
    void aNegativeAmountIsRejected() {
        Order order = newOrder(1L, "key-1");
        order.setAmount(new BigDecimal("-0.01"));

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(order);
            entityManager.flush();
        });
    }

    @Test
    void theSameBuyerCannotReuseAnIdempotencyKey() {
        entityManager.persist(newOrder(1L, "key-1"));
        entityManager.flush();

        Order retry = newOrder(1L, "key-1");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(retry);
            entityManager.flush();
        });
    }

    /** The key is scoped to the buyer, so two buyers cannot collide. */
    @Test
    void differentBuyersMayUseTheSameIdempotencyKey() {
        entityManager.persist(newOrder(1L, "key-1"));
        entityManager.persist(newOrder(2L, "key-1"));
        entityManager.flush();
    }

    /**
     * The safety net behind the one-payment invariant: one captured payment
     * must never be attached to two orders.
     */
    @Test
    void twoOrdersCannotShareAPaymentRef() {
        Order first = newOrder(1L, "key-1");
        first.setPaymentRef("pay_abc123");
        entityManager.persist(first);
        entityManager.flush();

        Order second = newOrder(2L, "key-2");
        second.setPaymentRef("pay_abc123");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(second);
            entityManager.flush();
        });
    }

    /**
     * Pins a requirement, not the index's shape: every order sits at
     * payment_ref IS NULL until initiate is saved, so many must coexist there.
     *
     * <p>This passes because Postgres treats NULLs in a unique index as
     * distinct — which means a <em>total</em> UNIQUE would pass it too. It is
     * therefore not a test of {@code uk_orders_payment_ref} being partial;
     * nothing can be, since the two behave identically. What it does catch is
     * the assumption underneath changing: {@code NULLS NOT DISTINCT}, or a
     * move to a database that folds NULLs together.
     */
    @Test
    void manyOrdersMayHaveNoPaymentRefAtAll() {
        entityManager.persist(newOrder(1L, "key-1"));
        entityManager.persist(newOrder(2L, "key-2"));
        entityManager.persist(newOrder(3L, "key-3"));
        entityManager.flush();

        Object unpaid = entityManager
                .createNativeQuery("select count(*) from orders where payment_ref is null")
                .getSingleResult();

        assertEquals(3L, ((Number) unpaid).longValue());
    }

    /**
     * The mapping's half of the guard. This one never reaches Postgres:
     * Hibernate's own {@code nullable = false} check rejects it first, so it
     * passes with or without the NOT NULL in V2 and cannot speak for the
     * database. It is kept because the mapping is worth pinning too, but the
     * test below is the one that guards the column.
     */
    @Test
    void hibernateRejectsANullExpiresAtBeforeItReachesTheDatabase() {
        Order order = newOrder(1L, "key-1");
        order.setExpiresAt(null);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(order);
            entityManager.flush();
        });
    }

    /**
     * Raw SQL, to get past Hibernate and put the question to Postgres: a NULL
     * deadline is never "less than now()", so such a row would be invisible to
     * the expiry sweep and hold its reserved stock forever. Production writes
     * this column in raw SQL too, so this is also the path that can actually
     * produce the bug.
     */
    @Test
    void databaseRejectsANullExpiresAt() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at) "
                                + "values (1, 7, 'CREATED', 'key-x', null)")
                .executeUpdate());
    }

    /**
     * Deliberately no clear()/find(): this asserts on the instance that was
     * just persisted. A re-read would pass with or without {@code @Generated},
     * because the row always holds what the DEFAULT wrote; only this can see a
     * field left null on the object the caller is still holding.
     */
    @Test
    void createdAtAndUpdatedAtAreReadBackOntoTheInstanceThatWasPersisted() {
        Order order = newOrder(1L, "key-1");

        entityManager.persist(order);
        entityManager.flush();

        assertNotNull(order.getCreatedAt(),
                "created_at was not read back after insert, is @Generated missing?");
        assertNotNull(order.getUpdatedAt(),
                "updated_at was not read back after insert, is @Generated missing?");
    }

    /**
     * The reason updated_at is a trigger and not {@code @UpdateTimestamp}.
     * Every status write in production is raw CAS SQL exactly like this one,
     * which Hibernate's dirty-checking never sees, so a Java-stamped column
     * would silently stop moving on the only writes that matter.
     *
     * <p>No sleep is needed: bump_updated_at uses GREATEST(clock_timestamp(),
     * OLD.updated_at + 1 microsecond), so the value is strictly increasing
     * even for two updates inside the same microsecond.
     */
    @Test
    void updatedAtMovesWhenTheRowIsChangedByRawSql() {
        Order order = newOrder(1L, "key-1");
        entityManager.persist(order);
        entityManager.flush();
        Instant beforeUpdate = order.getUpdatedAt();

        entityManager.createNativeQuery("update orders set status = 'ORDER_EXPIRED' where id = :id")
                .setParameter("id", order.getId())
                .executeUpdate();
        entityManager.refresh(order);

        assertTrue(order.getUpdatedAt().isAfter(beforeUpdate),
                "the trigger did not bump updated_at on a raw SQL update");
    }

    @Test
    void createdAtDoesNotMoveWhenTheRowIsChangedByRawSql() {
        Order order = newOrder(1L, "key-1");
        entityManager.persist(order);
        entityManager.flush();
        Instant createdAt = order.getCreatedAt();

        entityManager.createNativeQuery("update orders set status = 'ORDER_EXPIRED' where id = :id")
                .setParameter("id", order.getId())
                .executeUpdate();
        entityManager.refresh(order);

        assertEquals(createdAt, order.getCreatedAt());
    }
}
