package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.StockReservation;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Finders only. The replay check of {@code reserve} reads through this. */
public interface StockReservationRepository extends Repository<StockReservation, Long> {

    Optional<StockReservation> findByOrderIdAndFlashSaleItemId(Long orderId, Long flashSaleItemId);
}
