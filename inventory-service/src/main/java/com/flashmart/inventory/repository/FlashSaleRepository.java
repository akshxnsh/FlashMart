package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.FlashSale;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Finders only. Liveness is computed in SQL at reserve time, never read from here. */
public interface FlashSaleRepository extends Repository<FlashSale, Long> {

    Optional<FlashSale> findById(Long id);
}
