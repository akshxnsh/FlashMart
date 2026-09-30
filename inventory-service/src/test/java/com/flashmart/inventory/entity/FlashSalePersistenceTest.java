package com.flashmart.inventory.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Transactional
class FlashSalePersistenceTest {

    private static final LocalDateTime START = LocalDateTime.of(2030, 1, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2030, 1, 1, 12, 0);

    @Autowired
    private EntityManager entityManager;

    private FlashSale newSale() {
        FlashSale sale = new FlashSale();
        sale.setBrandId(1L);
        sale.setName("Persistence Probe Sale");
        sale.setStartTime(START);
        sale.setEndTime(END);
        sale.setStatus(FlashSaleStatus.ACTIVE);
        return sale;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        FlashSale sale = newSale();
        entityManager.persist(sale);
        entityManager.flush();
        entityManager.clear();

        FlashSale loaded = entityManager.find(FlashSale.class, sale.getId());

        assertNotNull(loaded);
        assertEquals(1L, loaded.getBrandId());
        assertEquals("Persistence Probe Sale", loaded.getName());
        assertEquals(START, loaded.getStartTime());
        assertEquals(END, loaded.getEndTime());
        assertEquals(FlashSaleStatus.ACTIVE, loaded.getStatus());
    }

    @Test
    void statusIsStoredAsTextNotAsANumber() {
        FlashSale sale = newSale();
        entityManager.persist(sale);
        entityManager.flush();

        Object raw = entityManager
                .createNativeQuery("select status from flash_sales where id = :id")
                .setParameter("id", sale.getId())
                .getSingleResult();

        assertEquals("ACTIVE", raw);
    }
}
