package com.flashmart.inventory.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class StockReservationPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    private Long persistedItemId;

    private void persistParents() {
        Product product = new Product();
        product.setBrandId(1L);
        product.setName("Reservation Probe Product");
        entityManager.persist(product);

        FlashSale sale = new FlashSale();
        sale.setBrandId(1L);
        sale.setName("Reservation Probe Sale");
        sale.setStartTime(Instant.parse("2030-01-01T10:00:00Z"));
        sale.setEndTime(Instant.parse("2030-01-01T12:00:00Z"));
        sale.setStatus(FlashSaleStatus.ACTIVE);
        entityManager.persist(sale);
        entityManager.flush();

        FlashSaleItem item = new FlashSaleItem();
        item.setFlashSaleId(sale.getId());
        item.setProductId(product.getId());
        item.setSalePrice(new BigDecimal("99.99"));
        item.setTotalStock(100);
        item.setAvailableStock(100);
        entityManager.persist(item);
        entityManager.flush();
        persistedItemId = item.getId();
    }

    private StockReservation newReservation(long orderId, int quantity) {
        StockReservation reservation = new StockReservation();
        reservation.setOrderId(orderId);
        reservation.setFlashSaleItemId(persistedItemId);
        reservation.setQuantity(quantity);
        reservation.setStatus(StockReservationStatus.RESERVED);
        return reservation;
    }

    @Test
    void saveAndReloadKeepsEveryField() {
        persistParents();
        StockReservation reservation = newReservation(42L, 3);
        entityManager.persist(reservation);
        entityManager.flush();
        entityManager.clear();

        StockReservation loaded = entityManager.find(StockReservation.class, reservation.getId());

        assertNotNull(loaded);
        assertEquals(42L, loaded.getOrderId());
        assertEquals(persistedItemId, loaded.getFlashSaleItemId());
        assertEquals(3, loaded.getQuantity());
        assertEquals(StockReservationStatus.RESERVED, loaded.getStatus());
    }

    @Test
    void reservedAtIsStampedByTheDatabase() {
        persistParents();
        StockReservation reservation = newReservation(42L, 3);
        entityManager.persist(reservation);
        entityManager.flush();
        entityManager.clear();

        StockReservation loaded = entityManager.find(StockReservation.class, reservation.getId());

        assertNotNull(loaded.getReservedAt());
    }

    @Test
    void statusIsStoredAsTextNotAsANumber() {
        persistParents();
        StockReservation reservation = newReservation(42L, 3);
        entityManager.persist(reservation);
        entityManager.flush();

        Object raw = entityManager
                .createNativeQuery("select status from stock_reservations where id = :id")
                .setParameter("id", reservation.getId())
                .getSingleResult();

        assertEquals("RESERVED", raw);
    }

    @Test
    void sameOrderCannotReserveTheSameItemTwice() {
        persistParents();
        entityManager.persist(newReservation(42L, 3));
        entityManager.flush();

        StockReservation retry = newReservation(42L, 3);

        assertThrows(PersistenceException.class, () -> {
            entityManager.persist(retry);
            entityManager.flush();
        });
    }

    @Test
    void differentOrdersCanReserveTheSameItem() {
        persistParents();
        entityManager.persist(newReservation(42L, 3));
        entityManager.persist(newReservation(43L, 1));
        entityManager.flush();
    }
}
