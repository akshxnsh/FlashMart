package com.flashmart.inventory.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class FlashSaleItemPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private Long persistedSaleId;
    private Long persistedProductId;

    private void persistParents() {
        Product product = new Product();
        product.setBrandId(1L);
        product.setName("Item Probe Product");
        entityManager.persist(product);

        FlashSale sale = new FlashSale();
        sale.setBrandId(1L);
        sale.setName("Item Probe Sale");
        sale.setStartTime(LocalDateTime.of(2030, 1, 1, 10, 0));
        sale.setEndTime(LocalDateTime.of(2030, 1, 1, 12, 0));
        sale.setStatus(FlashSaleStatus.ACTIVE);
        entityManager.persist(sale);

        entityManager.flush();
        persistedProductId = product.getId();
        persistedSaleId = sale.getId();
    }

    private FlashSaleItem newItem(int totalStock, int availableStock) {
        FlashSaleItem item = new FlashSaleItem();
        item.setFlashSaleId(persistedSaleId);
        item.setProductId(persistedProductId);
        item.setSalePrice(new BigDecimal("99.99"));
        item.setTotalStock(totalStock);
        item.setAvailableStock(availableStock);
        return item;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        persistParents();
        FlashSaleItem item = newItem(100, 100);
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        FlashSaleItem loaded = entityManager.find(FlashSaleItem.class, item.getId());

        assertNotNull(loaded);
        assertEquals(persistedSaleId, loaded.getFlashSaleId());
        assertEquals(persistedProductId, loaded.getProductId());
        assertEquals(0, new BigDecimal("99.99").compareTo(loaded.getSalePrice()));
        assertEquals(100, loaded.getTotalStock());
        assertEquals(100, loaded.getAvailableStock());
    }

    @Test
    void versionStartsAtZeroAndEveryUpdateBumpsIt() {
        persistParents();
        FlashSaleItem item = newItem(100, 100);
        entityManager.persist(item);
        entityManager.flush();
        assertEquals(0L, item.getVersion());

        item.setAvailableStock(99);
        entityManager.flush();
        assertEquals(1L, item.getVersion());

        item.setAvailableStock(98);
        entityManager.flush();
        assertEquals(2L, item.getVersion());
    }

    @Test
    void availableStockAboveTotalStockIsRejected() {
        persistParents();
        FlashSaleItem item = newItem(5, 10);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(item);
            entityManager.flush();
        });
    }

    @Test
    void sameProductCannotBeListedTwiceInOneSale() {
        persistParents();
        entityManager.persist(newItem(10, 10));
        entityManager.flush();

        FlashSaleItem duplicate = newItem(20, 20);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        });
    }
}
