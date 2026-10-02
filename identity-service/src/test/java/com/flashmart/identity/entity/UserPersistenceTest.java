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
class UserPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private User newUser(String email) {
        User user = new User();
        user.setName("Priya Sharma");
        user.setEmail(email);
        user.setPasswordHash("not-a-real-hash");
        return user;
    }

    private void insertRaw(String name, String email) {
        entityManager.createNativeQuery(
                        "insert into users (name, email, password_hash) values (:n, :e, 'x')")
                .setParameter("n", name)
                .setParameter("e", email)
                .executeUpdate();
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        User user = newUser("priya@example.com");
        entityManager.persist(user);
        entityManager.flush();
        entityManager.clear();

        User loaded = entityManager.find(User.class, user.getId());

        assertNotNull(loaded);
        assertEquals("Priya Sharma", loaded.getName());
        assertEquals("priya@example.com", loaded.getEmail());
        assertEquals("not-a-real-hash", loaded.getPasswordHash());
        assertNotNull(loaded.getCreatedAt());
        assertNotNull(loaded.getUpdatedAt());
    }

    @Test
    void everyUpdateMovesUpdatedAtStrictlyForward() {
        User user = newUser("forward@example.com");
        entityManager.persist(user);
        entityManager.flush();
        Instant previous = user.getUpdatedAt();

        for (int i = 1; i <= 3; i++) {
            user.setName("Priya " + i);
            entityManager.flush();
            assertTrue(user.getUpdatedAt().isAfter(previous), "update " + i + " did not move updated_at forward");
            previous = user.getUpdatedAt();
        }
    }

    @Test
    void updatedAtStillMovesForwardWhenTheRowCarriesATimeAheadOfTheClock() {
        Instant aheadOfClock = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        entityManager.createNativeQuery(
                        "insert into users (id, name, email, password_hash, created_at, updated_at) "
                                + "values (9001, 'Ahead', 'ahead@example.com', 'x', :t, :t)")
                .setParameter("t", aheadOfClock)
                .executeUpdate();
        entityManager.clear();

        User user = entityManager.find(User.class, 9001L);
        user.setName("Ahead Renamed");
        entityManager.flush();

        assertTrue(user.getUpdatedAt().isAfter(aheadOfClock));
    }

    @Test
    void emailIsTrimmedAndLowercasedBeforeItIsStored() {
        User user = newUser("  Priya@EXAMPLE.com ");
        entityManager.persist(user);
        entityManager.flush();

        assertEquals("priya@example.com", user.getEmail());
    }

    @Test
    void twoUsersCannotShareAnEmailEvenInADifferentCase() {
        entityManager.persist(newUser("same@example.com"));
        entityManager.flush();

        User duplicate = newUser("SAME@Example.com");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        });
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
    void databaseRejectsAnEmailWithNothingBeforeOrAfterTheAtSign() {
        assertThrows(PersistenceException.class, () -> insertRaw("X", "@example.com"));
        assertThrows(PersistenceException.class, () -> insertRaw("X", "priya@"));
    }

    @Test
    void databaseRejectsABlankName() {
        assertThrows(PersistenceException.class, () -> insertRaw("   ", "blank@example.com"));
    }

    @Test
    void aBrandAndAUserMayShareAnEmail() {
        Brand brand = new Brand();
        brand.setName("Puma");
        brand.setEmail("shared@example.com");
        brand.setPasswordHash("x");
        entityManager.persist(brand);
        entityManager.persist(newUser("shared@example.com"));
        entityManager.flush();
    }
}
