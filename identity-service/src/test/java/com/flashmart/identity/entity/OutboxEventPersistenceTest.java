package com.flashmart.identity.entity;

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

@SpringBootTest
@Transactional
class OutboxEventPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private OutboxEvent brandUpserted(long brandId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(brandId);
        event.setEventType("BrandUpserted");
        event.setPayloadJson("{\"brand_id\":" + brandId + ",\"email\":\"contact@puma.com\"}");
        return event;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        OutboxEvent event = brandUpserted(12L);
        entityManager.persist(event);
        entityManager.flush();
        entityManager.clear();

        OutboxEvent loaded = entityManager.find(OutboxEvent.class, event.getId());

        assertNotNull(loaded);
        assertEquals(12L, loaded.getAggregateId());
        assertEquals("BrandUpserted", loaded.getEventType());
        assertTrue(loaded.getPayloadJson().contains("contact@puma.com"));
        assertNotNull(loaded.getCreatedAt());
    }

    @Test
    void aNewRowIsUnpublishedWithNoAttempts() {
        OutboxEvent event = brandUpserted(12L);
        entityManager.persist(event);
        entityManager.flush();
        entityManager.clear();

        OutboxEvent loaded = entityManager.find(OutboxEvent.class, event.getId());

        assertNull(loaded.getPublishedAt(), "a new row has not been published");
        assertEquals(0, loaded.getAttempts());
        assertNull(loaded.getLastError());
    }

    @Test
    void publishingStampsTheTimeAndRemovesTheRowFromTheRelaysView() {
        OutboxEvent event = brandUpserted(12L);
        entityManager.persist(event);
        entityManager.flush();

        assertEquals(1, unpublishedIds().size());

        event.setPublishedAt(Instant.now());
        entityManager.flush();

        assertTrue(unpublishedIds().isEmpty(), "a published row must leave the relay's query");
    }

    @Test
    void aFailedAttemptIsRecordedOnTheRowAndTheRowStaysUnpublished() {
        OutboxEvent event = brandUpserted(12L);
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
        OutboxEvent first = brandUpserted(1L);
        entityManager.persist(first);
        entityManager.flush();
        OutboxEvent second = brandUpserted(2L);
        entityManager.persist(second);
        entityManager.flush();

        assertEquals(List.of(first.getId(), second.getId()), unpublishedIds());
    }

    @Test
    void payloadIsStoredAsJsonbSoInvalidJsonIsRejected() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into outbox (aggregate_id, event_type, payload_json) "
                                + "values (1, 'BrandUpserted', 'not json'::jsonb)")
                .executeUpdate());
    }

    @Test
    void payloadIsQueryableAsJsonNotJustText() {
        OutboxEvent event = brandUpserted(12L);
        entityManager.persist(event);
        entityManager.flush();

        Object email = entityManager.createNativeQuery(
                        "select payload_json ->> 'email' from outbox where id = :id")
                .setParameter("id", event.getId())
                .getSingleResult();

        assertEquals("contact@puma.com", email);
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
                                + "values (1, 'BrandUpserted', '{}'::jsonb, -1)")
                .executeUpdate());
    }

    @Test
    void oneBrandIdAndOneUserIdMayShareANumberBecauseEventTypeTellsThemApart() {
        OutboxEvent brandEvent = brandUpserted(7L);
        OutboxEvent userEvent = new OutboxEvent();
        userEvent.setAggregateId(7L);
        userEvent.setEventType("UserUpserted");
        userEvent.setPayloadJson("{\"user_id\":7,\"email\":\"priya@example.com\"}");

        entityManager.persist(brandEvent);
        entityManager.persist(userEvent);
        entityManager.flush();
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
