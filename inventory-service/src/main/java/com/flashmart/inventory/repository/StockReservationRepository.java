package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.StockReservation;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * The replay check of {@code reserve} reads through the finder; the insert goes
 * through {@code save}. {@code UNIQUE(order_id, flash_sale_item_id)} is what
 * stops a second reservation for the same order and item.
 */
public interface StockReservationRepository extends Repository<StockReservation, Long> {

    Optional<StockReservation> findByOrderIdAndFlashSaleItemId(Long orderId, Long flashSaleItemId);

    StockReservation save(StockReservation reservation);
}
