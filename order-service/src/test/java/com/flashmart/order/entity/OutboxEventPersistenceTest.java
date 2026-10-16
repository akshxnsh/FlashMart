package com.flashmart.order.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately the same tests as Identity's {@code OutboxEventPersistenceTest},
 * because the relay logic is the same and the two shapes must not drift. Only
 * the vocabulary differs, and the tests that pin it are the ones that differ.
 */
@SpringBootTest
@Transactional
class OutboxEventPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private OutboxEvent paymentConfirmed(long orderId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(orderId);
        event.setEventType("PaymentConfirmed");
        event.setPayloadJson("{\"order_id\":" + orderId + ",\"amount\":\"199.98\"}");
        return event;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        OutboxEvent event = paymentConfirmed(12L);
        entityManager.persist(event);
        entityManager.flush();
        entityManager.clear();

        OutboxEvent loaded = entityManager.find(OutboxEvent.class, event.getId());

        assertNotNull(loaded);
        assertEquals(12L, loaded.getAggregateId());
        assertEquals("PaymentConfirmed", loaded.getEventType());
        assertTrue(loaded.getPayloadJson().contains("199.98"));
        assertNotNull(loaded.getCreatedAt());
    }

    @Test
    void aNewRowIsUnpublishedWithNoAttempts() {
        OutboxEvent event = paymentConfirmed(12L);
        entityManager.persist(event);
        entityManager.flush();
        entityManager.clear();

        OutboxEvent loaded = entityManager.find(OutboxEvent.class, event.getId());

        assertNull(loaded.getPublishedAt(), "a new row has not been published");
        assertEquals(0, loaded.getAttempts());
        assertNull(loaded.getLastError());
    }

    /** As in Order and Refund: a re-read cannot see a null left on the instance. */
    @Test
    void createdAtIsReadBackOntoTheInstanceThatWasPersisted() {
        OutboxEvent event = paymentConfirmed(12L);

        entityManager.persist(event);
        entityManager.flush();

        assertNotNull(event.getCreatedAt(),
                "created_at was not read back after insert, is @Generated missing?");
    }

    @Test
    void publishingStampsTheTimeAndRemovesTheRowFromTheRelaysView() {
        OutboxEvent event = paymentConfirmed(12L);
        entityManager.persist(event);
        entityManager.flush();

        assertEquals(1, unpublishedIds().size());

        event.setPublishedAt(Instant.now());
        entityManager.flush();

        assertTrue(unpublishedIds().isEmpty(), "a published row must leave the relay's query");
    }

    @Test
    void aFailedAttemptIsRecordedOnTheRowAndTheRowStaysUnpublished() {
        OutboxEvent event = paymentConfirmed(12L);
        entityManager.persist(event);
        entityManager.flush();

        event.setAttempts(event.getAttempts() + 1);
        event.setLastError("broker unavailable");
        entityManager.flush();
        entityManager.clear();

        OutboxEvent loaded = entityManager.find(OutboxEvent.class, event.getId());
        assertEquals(1, loaded.getAttempts());
        assertEquals("broker unavailable", loaded.getLastError());
        assertNull(loaded.getPublishedAt());
        assertTrue(unpublishedIds().contains(loaded.getId()), "a failed row must still be retried");
    }

    @Test
    void theRelayReadsOldestFirst() {
        OutboxEvent first = paymentConfirmed(1L);
        entityManager.persist(first);
        entityManager.flush();
        OutboxEvent second = paymentConfirmed(2L);
        entityManager.persist(second);
        entityManager.flush();

        assertEquals(List.of(first.getId(), second.getId()), unpublishedIds());
    }

    @Test
    void payloadIsStoredAsJsonbSoInvalidJsonIsRejected() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into outbox (aggregate_id, event_type, payload_json) "
                                + "values (1, 'PaymentConfirmed', 'not json'::jsonb)")
                .executeUpdate());
    }

    @Test
    void payloadIsQueryableAsJsonNotJustText() {
        OutboxEvent event = paymentConfirmed(12L);
        entityManager.persist(event);
        entityManager.flush();

        Object amount = entityManager.createNativeQuery(
                        "select payload_json ->> 'amount' from outbox where id = :id")
                .setParameter("id", event.getId())
                .getSingleResult();

        assertEquals("199.98", amount);
    }

    /** Both of Order's two events, and only these two. */
    @Test
    void bothOfOrdersEventTypesAreAccepted() {
        OutboxEvent confirmed = paymentConfirmed(1L);

        OutboxEvent cancelled = new OutboxEvent();
        cancelled.setAggregateId(2L);
        cancelled.setEventType("OrderCancelled");
        cancelled.setPayloadJson("{\"order_id\":2,\"reason\":\"ORDER_EXPIRED\"}");

        entityManager.persist(confirmed);
        entityManager.persist(cancelled);
        entityManager.flush();
    }

    /**
     * The vocabularies are per-service on purpose. Order has no business
     * publishing a brand event, and this constraint is what says so, rather
     * than a convention nobody checks.
     */
    @Test
    void databaseRejectsIdentitysEventTypes() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into outbox (aggregate_id, event_type, payload_json) "
                                + "values (1, 'BrandUpserted', '{}'::jsonb)")
                .executeUpdate());
    }

    @Test
    void databaseRejectsAnUnknownEventType() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into outbox (aggregate_id, event_type, payload_json) "
                                + "values (1, 'OrderShipped', '{}'::jsonb)")
                .executeUpdate());
    }

    @Test
    void databaseRejectsAnEventTypeThatDiffersOnlyInCase() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into outbox (aggregate_id, event_type, payload_json) "
                                + "values (1, 'paymentconfirmed', '{}'::jsonb)")
                .executeUpdate());
    }

    @Test
    void databaseRejectsABlankEventType() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into outbox (aggregate_id, event_type, payload_json) "
                                + "values (1, '   ', '{}'::jsonb)")
                .executeUpdate());
    }

    @Test
    void databaseRejectsNegativeAttempts() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into outbox (aggregate_id, event_type, payload_json, attempts) "
                                + "values (1, 'PaymentConfirmed', '{}'::jsonb, -1)")
                .executeUpdate());
    }

    @SuppressWarnings("unchecked")
    private List<Long> unpublishedIds() {
        entityManager.flush();
        return entityManager.createNativeQuery(
                        "select id from outbox where published_at is null order by created_at, id")
                .getResultList()
                .stream()
                .map(value -> ((Number) value).longValue())
                .toList();
    }
}
