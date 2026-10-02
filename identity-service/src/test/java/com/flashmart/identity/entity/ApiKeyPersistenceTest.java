package com.flashmart.identity.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class ApiKeyPersistenceTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private EntityManager entityManager;

    private Long persistBrand(String email) {
        Brand brand = new Brand();
        brand.setName("Puma");
        brand.setEmail(email);
        brand.setPasswordHash("x");
        entityManager.persist(brand);
        entityManager.flush();
        return brand.getId();
    }

    private ApiKey activeKey(Long brandId, String keyHash) {
        ApiKey key = new ApiKey();
        key.setBrandId(brandId);
        key.setKeyHash(keyHash);
        key.setStatus(ApiKeyStatus.ACTIVE);
        return key;
    }

    private void insertRawKey(Long brandId, String keyHash) {
        entityManager.createNativeQuery(
                        "insert into api_keys (brand_id, key_hash, status) values (:b, :k, 'ACTIVE')")
                .setParameter("b", brandId)
                .setParameter("k", keyHash)
                .executeUpdate();
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        Long brandId = persistBrand("roundtrip@puma.com");
        ApiKey key = activeKey(brandId, HASH_A);
        entityManager.persist(key);
        entityManager.flush();
        entityManager.clear();

        ApiKey loaded = entityManager.find(ApiKey.class, key.getId());

        assertNotNull(loaded);
        assertEquals(brandId, loaded.getBrandId());
        assertEquals(HASH_A, loaded.getKeyHash());
        assertEquals(ApiKeyStatus.ACTIVE, loaded.getStatus());
        assertNotNull(loaded.getCreatedAt());
        assertNull(loaded.getRevokedAt(), "a live key has no revocation time");
    }

    @Test
    void statusIsStoredAsTextNotAsANumber() {
        Long brandId = persistBrand("text@puma.com");
        ApiKey key = activeKey(brandId, HASH_A);
        entityManager.persist(key);
        entityManager.flush();

        Object raw = entityManager
                .createNativeQuery("select status from api_keys where id = :id")
                .setParameter("id", key.getId())
                .getSingleResult();

        assertEquals("ACTIVE", raw);
    }

    @Test
    void revokingSetsBothStatusAndRevokedAt() {
        Long brandId = persistBrand("revoke@puma.com");
        ApiKey key = activeKey(brandId, HASH_A);
        entityManager.persist(key);
        entityManager.flush();

        key.setStatus(ApiKeyStatus.REVOKED);
        key.setRevokedAt(Instant.now());
        entityManager.flush();
        entityManager.clear();

        ApiKey loaded = entityManager.find(ApiKey.class, key.getId());
        assertEquals(ApiKeyStatus.REVOKED, loaded.getStatus());
        assertNotNull(loaded.getRevokedAt());
    }

    @Test
    void aRevokedKeyMustCarryARevocationTime() {
        Long brandId = persistBrand("halfrevoked@puma.com");
        ApiKey key = activeKey(brandId, HASH_A);
        entityManager.persist(key);
        entityManager.flush();

        key.setStatus(ApiKeyStatus.REVOKED);

        assertThrows(PersistenceException.class, () -> entityManager.flush());
    }

    @Test
    void anActiveKeyMustNotCarryARevocationTime() {
        Long brandId = persistBrand("activerevoked@puma.com");
        ApiKey key = activeKey(brandId, HASH_A);
        key.setRevokedAt(Instant.now());

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(key);
            entityManager.flush();
        });
    }

    @Test
    void theSameKeyHashCannotBeStoredTwiceEvenForAnotherBrand() {
        Long first = persistBrand("first@puma.com");
        Long second = persistBrand("second@puma.com");
        entityManager.persist(activeKey(first, HASH_A));
        entityManager.flush();

        ApiKey duplicate = activeKey(second, HASH_A);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        });
    }

    @Test
    void aBrandMayHoldSeveralActiveKeysSoRotationNeedsNoDowntime() {
        Long brandId = persistBrand("rotate@puma.com");
        entityManager.persist(activeKey(brandId, HASH_A));
        entityManager.persist(activeKey(brandId, HASH_B));
        entityManager.flush();
    }

    @Test
    void databaseRejectsAnUppercaseHash() {
        Long brandId = persistBrand("upper@puma.com");
        assertThrows(PersistenceException.class, () -> insertRawKey(brandId, "A".repeat(64)));
    }

    @Test
    void databaseRejectsAHashOfTheWrongLength() {
        Long brandId = persistBrand("short@puma.com");
        assertThrows(PersistenceException.class, () -> insertRawKey(brandId, "abc123"));
    }

    @Test
    void databaseRejectsABcryptHashBecauseItCouldNeverBeLookedUpByEquality() {
        Long brandId = persistBrand("bcrypt@puma.com");
        assertThrows(PersistenceException.class,
                () -> insertRawKey(brandId, "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
    }

    @Test
    void databaseRejectsAKeyForABrandThatDoesNotExist() {
        assertThrows(PersistenceException.class, () -> insertRawKey(999999L, HASH_A));
    }
}
