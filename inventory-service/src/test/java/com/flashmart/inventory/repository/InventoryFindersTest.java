package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.FlashSale;
import com.flashmart.inventory.entity.FlashSaleItem;
import com.flashmart.inventory.entity.FlashSaleStatus;
import com.flashmart.inventory.entity.Product;
import com.flashmart.inventory.entity.StockReservation;
import com.flashmart.inventory.entity.StockReservationStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finder tests only: the stock write path is an open decision and has no repository method. */
@SpringBootTest
@Transactional
class InventoryFindersTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private FlashSaleRepository sales;

    @Autowired
    private FlashSaleItemRepository items;

    @Autowired
    private StockReservationRepository reservations;

    private FlashSale sale;
    private Product product;
    private FlashSaleItem item;
    private StockReservation reservation;

    @BeforeEach
    void seed() {
        product = new Product();
        product.setBrandId(1L);
        product.setName("Finder Probe Product");
        entityManager.persist(product);

        sale = new FlashSale();
        sale.setBrandId(1L);
        sale.setName("Finder Probe Sale");
        sale.setStartTime(Instant.parse("2030-01-01T10:00:00Z"));
        sale.setEndTime(Instant.parse("2030-01-01T12:00:00Z"));
        sale.setStatus(FlashSaleStatus.ACTIVE);
        entityManager.persist(sale);
        entityManager.flush();

        item = new FlashSaleItem();
        item.setFlashSaleId(sale.getId());
        item.setProductId(product.getId());
        item.setSalePrice(new BigDecimal("99.99"));
        item.setTotalStock(100);
        item.setAvailableStock(100);
        entityManager.persist(item);
        entityManager.flush();

        reservation = new StockReservation();
        reservation.setOrderId(42L);
        reservation.setFlashSaleItemId(item.getId());
        reservation.setQuantity(2);
        reservation.setStatus(StockReservationStatus.RESERVED);
        entityManager.persist(reservation);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void flashSaleIsFoundById() {
        assertEquals(sale.getId(), sales.findById(sale.getId()).orElseThrow().getId());
        assertTrue(sales.findById(-1L).isEmpty());
    }

    @Test
    void itemIsFoundBySaleAndProduct() {
        FlashSaleItem found = items.findByFlashSaleIdAndProductId(sale.getId(), product.getId()).orElseThrow();

        assertEquals(item.getId(), found.getId());
        assertEquals(100, found.getAvailableStock());
    }

    /** The same product in a different sale is a different item, or not an item at all. */
    @Test
    void itemIsNotFoundForADifferentSaleOrProduct() {
        assertTrue(items.findByFlashSaleIdAndProductId(sale.getId() + 1_000, product.getId()).isEmpty());
        assertTrue(items.findByFlashSaleIdAndProductId(sale.getId(), product.getId() + 1_000).isEmpty());
    }

    @Test
    void reservationIsFoundByOrderAndItem() {
        StockReservation found = reservations
                .findByOrderIdAndFlashSaleItemId(42L, item.getId()).orElseThrow();

        assertEquals(reservation.getId(), found.getId());
        assertEquals(2, found.getQuantity());
    }

    @Test
    void reservationIsNotFoundForAnotherOrderOrItem() {
        assertTrue(reservations.findByOrderIdAndFlashSaleItemId(43L, item.getId()).isEmpty());
        assertTrue(reservations.findByOrderIdAndFlashSaleItemId(42L, item.getId() + 1_000).isEmpty());
    }
}
