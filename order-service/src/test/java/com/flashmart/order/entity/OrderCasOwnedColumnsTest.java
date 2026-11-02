package com.flashmart.order.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards A1: the CAS-owned columns of {@code orders} are written by INSERT and
 * afterwards only by raw conditional SQL. Every check reads the column back
 * with a native query, never {@code find()}/{@code refresh()}, because a
 * managed entity would hand back its own stale copy.
 */
@SpringBootTest
@Transactional
class OrderCasOwnedColumnsTest {

    private static final Instant DEADLINE = Instant.parse("2030-01-01T10:00:00Z");

    @Autowired
    private EntityManager entityManager;

    private Order persistedCreatedOrder() {
        Order order = new Order();
        order.setBuyerId(1L);
        order.setFlashSaleId(7L);
        order.setStatus(OrderStatus.CREATED);
        order.setIdempotencyKey("key-1");
        order.setExpiresAt(DEADLINE);
        entityManager.persist(order);
        entityManager.flush();
        return order;
    }

    private String statusInDatabase(Long id) {
        return (String) entityManager
                .createNativeQuery("select status from orders where id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    /**
     * The sweep wins a CAS to ORDER_EXPIRED while another thread still holds the
     * order as CREATED. That thread then saves an unrelated field. The sweep's
     * result must survive.
     */
    @Test
    void aStaleSaveOfAnotherFieldDoesNotOverwriteACasWrite() {
        Long id = persistedCreatedOrder().getId();
        entityManager.clear();
        Order managed = entityManager.find(Order.class, id); // still believes CREATED

        int swept = entityManager.createNativeQuery(
                        "update orders set status = 'ORDER_EXPIRED' where id = :id and status = 'CREATED'")
                .setParameter("id", id)
                .executeUpdate();
        assertEquals(1, swept, "the raw CAS should have matched the row");

        managed.setFailureDetail("stale writer touched an allowed field");
        entityManager.flush();

        assertEquals("ORDER_EXPIRED", statusInDatabase(id));
        assertEquals("stale writer touched an allowed field",
                entityManager.createNativeQuery("select failure_detail from orders where id = :id")
                        .setParameter("id", id).getSingleResult(),
                "the allowed field should still be written, or the test proves nothing");
    }

    /** updatable = false means a setter is silently ignored after insert. */
    @Test
    void settingStatusOnAManagedEntityIsIgnoredByTheDatabase() {
        Long id = persistedCreatedOrder().getId();
        entityManager.clear();
        Order managed = entityManager.find(Order.class, id);

        managed.setStatus(OrderStatus.ORDER_EXPIRED);
        entityManager.flush();

        assertEquals("CREATED", statusInDatabase(id));
    }
}
