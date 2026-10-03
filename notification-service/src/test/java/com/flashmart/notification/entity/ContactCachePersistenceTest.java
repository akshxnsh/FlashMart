package com.flashmart.notification.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class ContactCachePersistenceTest {

    private static final Instant T1 = Instant.parse("2026-10-03T09:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-03T09:00:01Z");

    @Autowired
    private EntityManager entityManager;

    /**
     * The real consumer statement. A stale event fails the WHERE and is
     * silently skipped, so correctness never depends on Kafka delivery order.
     */
    private void consumeBrandUpserted(long brandId, String email, Instant updatedAt) {
        entityManager.createNativeQuery(
                        "insert into brand_contacts (brand_id, email, updated_at) values (:id, :e, :t) "
                                + "on conflict (brand_id) do update "
                                + "set email = excluded.email, updated_at = excluded.updated_at "
                                + "where brand_contacts.updated_at < excluded.updated_at")
                .setParameter("id", brandId)
                .setParameter("e", email)
                .setParameter("t", updatedAt)
                .executeUpdate();
        entityManager.clear();
    }

    private String cachedBrandEmail(long brandId) {
        return entityManager.find(BrandContact.class, brandId).getEmail();
    }

    @Test
    void aBrandContactRoundTrips() {
        BrandContact contact = new BrandContact();
        contact.setBrandId(12L);
        contact.setEmail("contact@puma.com");
        contact.setUpdatedAt(T1);
        entityManager.persist(contact);
        entityManager.flush();
        entityManager.clear();

        BrandContact loaded = entityManager.find(BrandContact.class, 12L);
        assertNotNull(loaded);
        assertEquals("contact@puma.com", loaded.getEmail());
        assertEquals(T1, loaded.getUpdatedAt());
    }

    @Test
    void aBuyerContactRoundTrips() {
        BuyerContact contact = new BuyerContact();
        contact.setUserId(7L);
        contact.setEmail("priya@example.com");
        contact.setUpdatedAt(T1);
        entityManager.persist(contact);
        entityManager.flush();
        entityManager.clear();

        BuyerContact loaded = entityManager.find(BuyerContact.class, 7L);
        assertNotNull(loaded);
        assertEquals("priya@example.com", loaded.getEmail());
    }

    @Test
    void aBrandAndABuyerSharingANumberAreDifferentRows() {
        BrandContact brand = new BrandContact();
        brand.setBrandId(7L);
        brand.setEmail("contact@puma.com");
        brand.setUpdatedAt(T1);
        BuyerContact buyer = new BuyerContact();
        buyer.setUserId(7L);
        buyer.setEmail("priya@example.com");
        buyer.setUpdatedAt(T1);
        entityManager.persist(brand);
        entityManager.persist(buyer);
        entityManager.flush();
        entityManager.clear();

        assertEquals("contact@puma.com", entityManager.find(BrandContact.class, 7L).getEmail());
        assertEquals("priya@example.com", entityManager.find(BuyerContact.class, 7L).getEmail());
    }

    @Test
    void theFirstEventForABrandInsertsIt() {
        consumeBrandUpserted(12L, "contact@puma.com", T1);
        assertEquals("contact@puma.com", cachedBrandEmail(12L));
    }

    @Test
    void aNewerEventReplacesTheCachedAddress() {
        consumeBrandUpserted(12L, "old@puma.com", T1);
        consumeBrandUpserted(12L, "new@puma.com", T2);
        assertEquals("new@puma.com", cachedBrandEmail(12L));
    }

    @Test
    void anOlderEventArrivingLateIsSilentlySkipped() {
        consumeBrandUpserted(12L, "new@puma.com", T2);
        consumeBrandUpserted(12L, "old@puma.com", T1);
        assertEquals("new@puma.com", cachedBrandEmail(12L),
                "a late stale event must not overwrite newer data");
    }

    @Test
    void aRedeliveredEventWithTheSameTimestampChangesNothing() {
        consumeBrandUpserted(12L, "contact@puma.com", T1);
        consumeBrandUpserted(12L, "contact@puma.com", T1);
        assertEquals("contact@puma.com", cachedBrandEmail(12L));
    }

    @Test
    void changedThenChangedBackEndsCorrectEvenOutOfOrder() {
        // a@x -> b@x at T1, then b@x -> a@x at T2, delivered newest first
        consumeBrandUpserted(12L, "a@puma.com", T2);
        consumeBrandUpserted(12L, "b@puma.com", T1);
        assertEquals("a@puma.com", cachedBrandEmail(12L),
                "the latest change must win whichever order the events arrive in");
    }

    @Test
    void theSetterNormalisesTheEmail() {
        BrandContact contact = new BrandContact();
        contact.setBrandId(12L);
        contact.setEmail("  Contact@PUMA.com ");
        contact.setUpdatedAt(T1);
        entityManager.persist(contact);
        entityManager.flush();

        assertEquals("contact@puma.com", contact.getEmail());
    }

    @Test
    void databaseRejectsAnEmailThatWasNotNormalized() {
        assertThrows(PersistenceException.class,
                () -> consumeBrandUpserted(12L, "Mixed@Case.com", T1));
    }

    @Test
    void databaseRejectsAnEmptyEmail() {
        assertThrows(PersistenceException.class, () -> consumeBrandUpserted(12L, "", T1));
    }

    @Test
    void databaseRejectsAnEmailWithNoAtSign() {
        assertThrows(PersistenceException.class, () -> consumeBrandUpserted(12L, "notanemail", T1));
    }

    @Test
    void buyerContactsCarriesTheSameEmailGuards() {
        assertThrows(PersistenceException.class, () -> entityManager.createNativeQuery(
                        "insert into buyer_contacts (user_id, email, updated_at) values (7, 'Mixed@Case.com', :t)")
                .setParameter("t", T1)
                .executeUpdate());
    }
}
