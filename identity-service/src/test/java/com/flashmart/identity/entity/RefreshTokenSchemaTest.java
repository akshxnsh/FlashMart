package com.flashmart.identity.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V8's guards, with native SQL so Hibernate cannot reject a bad row before
 * Postgres sees it. Rolled back after each test.
 */
@SpringBootTest
@Transactional
class RefreshTokenSchemaTest {

    private static final String GOOD_HASH = "a".repeat(64);

    @Autowired
    private EntityManager em;

    private long userId;

    @BeforeEach
    void user() {
        userId = ((Number) em.createNativeQuery(
                "INSERT INTO users (name, email, password_hash) VALUES ('T', 'schema@refresh.test', 'x') RETURNING id")
                .getSingleResult()).longValue();
    }

    private void insert(Object user, String hash, String expiresSql, String createdSql) {
        em.createNativeQuery("INSERT INTO refresh_tokens (user_id, token_hash, expires_at"
                        + (createdSql == null ? "" : ", created_at") + ") VALUES (:u, :h, " + expiresSql
                        + (createdSql == null ? "" : ", " + createdSql) + ")")
                .setParameter("u", user)
                .setParameter("h", hash)
                .executeUpdate();
        em.flush();
    }

    private static String constraintOf(PersistenceException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("constraint")) {
                return t.getMessage();
            }
        }
        return String.valueOf(e);
    }

    @Test
    void aGoodRowGetsCreatedAtFromTheDatabaseAndStartsLive() {
        insert(userId, GOOD_HASH, "clock_timestamp() + interval '7 days'", null);
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT created_at, revoked_at FROM refresh_tokens WHERE token_hash = :h")
                .setParameter("h", GOOD_HASH).getSingleResult();
        assertNotNull(row[0]);
        assertNull(row[1]);
    }

    @Test
    void expiresAtNotAfterCreatedAtIsRejected() {
        PersistenceException e = assertThrows(PersistenceException.class,
                () -> insert(userId, GOOD_HASH, "timestamptz '2026-01-01 00:00:00+00'",
                        "timestamptz '2026-01-01 00:00:00+00'"));
        assertTrue(constraintOf(e).contains("ck_refresh_tokens_expires_after_created"), constraintOf(e));
    }

    @Test
    void anUppercaseOrRawTokenIsRejectedAsAHash() {
        PersistenceException upper = assertThrows(PersistenceException.class,
                () -> insert(userId, "A".repeat(64), "clock_timestamp() + interval '1 day'", null));
        assertTrue(constraintOf(upper).contains("ck_refresh_tokens_token_hash_shaped"), constraintOf(upper));
    }

    @Test
    void aRawBase64TokenIsRejectedAsAHash() {
        PersistenceException raw = assertThrows(PersistenceException.class,
                () -> insert(userId, "q1w2e3r4t5y6u7i8o9p0-_ASDFGHJKLZXCVBNMqwertyu", "clock_timestamp() + interval '1 day'", null));
        assertTrue(constraintOf(raw).contains("ck_refresh_tokens_token_hash_shaped"), constraintOf(raw));
    }

    @Test
    void aDuplicateHashIsRejected() {
        insert(userId, GOOD_HASH, "clock_timestamp() + interval '1 day'", null);
        PersistenceException e = assertThrows(PersistenceException.class,
                () -> insert(userId, GOOD_HASH, "clock_timestamp() + interval '1 day'", null));
        assertTrue(constraintOf(e).contains("uk_refresh_tokens_token_hash"), constraintOf(e));
    }

    @Test
    void anUnknownUserIsRejectedByTheForeignKey() {
        PersistenceException e = assertThrows(PersistenceException.class,
                () -> insert(-1L, GOOD_HASH, "clock_timestamp() + interval '1 day'", null));
        assertTrue(constraintOf(e).contains("fk_refresh_tokens_user"), constraintOf(e));
    }

    @Test
    void aNullUserIsRejected() {
        assertThrows(PersistenceException.class,
                () -> em.createNativeQuery("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
                        + "VALUES (NULL, :h, clock_timestamp() + interval '1 day')")
                        .setParameter("h", GOOD_HASH).executeUpdate());
    }

    @Test
    void theRevokeAllIndexCoversLiveRowsOnly() {
        String def = (String) em.createNativeQuery(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_refresh_tokens_user_active'")
                .getSingleResult();
        assertTrue(def.contains("(user_id)") && def.contains("WHERE (revoked_at IS NULL)"), def);
    }

    @Test
    void theEntityReadsARowBack() {
        insert(userId, GOOD_HASH, "clock_timestamp() + interval '1 day'", null);
        em.clear();
        RefreshToken token = em.createQuery("FROM RefreshToken WHERE tokenHash = :h", RefreshToken.class)
                .setParameter("h", GOOD_HASH).getSingleResult();
        assertEquals(userId, token.getUserId());
        assertNotNull(token.getExpiresAt());
        assertNotNull(token.getCreatedAt());
        assertNull(token.getRevokedAt());
    }
}
