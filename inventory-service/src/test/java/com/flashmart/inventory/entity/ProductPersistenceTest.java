package com.flashmart.inventory.entity;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class ProductPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistPopulatesGeneratedTimestamps() {
        Product product = new Product();
        product.setBrandId(1L);
        product.setName("Timestamp Probe");

        entityManager.persist(product);
        entityManager.flush();

        assertNotNull(product.getId());
        assertNotNull(product.getCreatedAt());
        assertNotNull(product.getUpdatedAt());
    }

    @Test
    void updateBumpsUpdatedAtButNotCreatedAt() throws InterruptedException {
        Product product = new Product();
        product.setBrandId(1L);
        product.setName("Update Probe");
        entityManager.persist(product);
        entityManager.flush();

        Instant createdAt = product.getCreatedAt();
        Instant firstUpdatedAt = product.getUpdatedAt();

        Thread.sleep(20);
        product.setName("Update Probe Renamed");
        entityManager.flush();

        assertEquals(createdAt, product.getCreatedAt());
        assertTrue(product.getUpdatedAt().isAfter(firstUpdatedAt));
    }

    @Test
    void sameBrandCannotHaveTwoProductsWithTheSameName() {
        Product first = new Product();
        first.setBrandId(1L);
        first.setName("Duplicate Probe");
        entityManager.persist(first);
        entityManager.flush();

        Product second = new Product();
        second.setBrandId(1L);
        second.setName("Duplicate Probe");

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(second);
            entityManager.flush();
        });
    }
}
