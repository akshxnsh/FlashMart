package com.flashmart.identity.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class BrandPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private Brand newBrand(String email) {
        Brand brand = new Brand();
        brand.setName("Puma");
        brand.setEmail(email);
        brand.setPasswordHash("not-a-real-hash");
        return brand;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        Brand brand = newBrand("contact@puma.com");
        entityManager.persist(brand);
        entityManager.flush();
        entityManager.clear();

        Brand loaded = entityManager.find(Brand.class, brand.getId());

        assertNotNull(loaded);
        assertEquals("Puma", loaded.getName());
        assertEquals("contact@puma.com", loaded.getEmail());
        assertEquals("not-a-real-hash", loaded.getPasswordHash());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
    }

    @Test
    void timestampsAreReadBackWithoutAReload() {
        Brand brand = newBrand("readback@puma.com");
        entityManager.persist(brand);
        entityManager.flush();

        assertNotNull(brand.getCreatedAt());
        assertNotNull(brand.getUpdatedAt());
    }

    @Test
    void everyUpdateMovesUpdatedAtStrictlyForward() {
        Brand brand = newBrand("forward@puma.com");
        entityManager.persist(brand);
        entityManager.flush();
        Instant previous = brand.getUpdatedAt();

        for (int i = 1; i <= 3; i++) {
            brand.setName("Puma " + i);
            entityManager.flush();
            assertTrue(brand.getUpdatedAt().isAfter(previous), "update " + i + " did not move updated_at forward");
            previous = brand.getUpdatedAt();
        }
    }

    @Test
    void updatedAtStillMovesForwardWhenTheRowCarriesATimeAheadOfTheClock() {
        Instant aheadOfClock = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        entityManager.createNativeQuery(
                        "insert into brands (id, name, email, password_hash, created_at, updated_at) "
                                + "values (9001, 'Nike', 'ahead@nike.com', 'x', :t, :t)")
                .setParameter("t", aheadOfClock)
                .executeUpdate();
        entityManager.clear();

        Brand brand = entityManager.find(Brand.class, 9001L);
        brand.setName("Nike Renamed");
        entityManager.flush();

        assertTrue(brand.getUpdatedAt().isAfter(aheadOfClock));
    }

    @Test
    void emailIsTrimmedAndLowercasedBeforeItIsStored() {
        Brand brand = newBrand("  Contact@PUMA.com ");
        entityManager.persist(brand);
        entityManager.flush();

        assertEquals("contact@puma.com", brand.getEmail());
    }

    private void insertRaw(String name, String email) {
        entityManager.createNativeQuery(
                        "insert into brands (name, email, password_hash) values (:n, :e, 'x')")
                .setParameter("n", name)
                .setParameter("e", email)
                .executeUpdate();
    }

    @Test
    void databaseRejectsAnEmailThatWasNotNormalized() {
        assertThrows(PersistenceException.class, () -> insertRaw("X", "Mixed@Case.com"));
    }

    @Test
    void databaseRejectsAnEmptyEmail() {
        assertThrows(PersistenceException.class, () -> insertRaw("X", ""));
    }

    @Test
    void databaseRejectsAnEmailWithNoAtSign() {
        assertThrows(PersistenceException.class, () -> insertRaw("X", "notanemail"));
    }

    @Test
    void databaseRejectsABlankName() {
        assertThrows(PersistenceException.class, () -> insertRaw("   ", "blank@puma.com"));
    }

    @Test
    void twoBrandsCannotShareAnEmail() {
        entityManager.persist(newBrand("same@puma.com"));
        entityManager.flush();

        Brand duplicate = newBrand("same@puma.com");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        });
    }

    @Test
    void theSameEmailInADifferentCaseIsStillADuplicate() {
        entityManager.persist(newBrand("case@puma.com"));
        entityManager.flush();

        Brand duplicate = newBrand("CASE@Puma.com");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        });
    }
}
