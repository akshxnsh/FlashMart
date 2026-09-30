package com.flashmart.inventory.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
