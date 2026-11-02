package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.FlashSaleItem;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Finders only. No stock decrement, no {@code @Lock}, no retry: the locking
 * strategy, isolation level and reserve write path are open decisions
 * (CLAUDE.md open decisions 1 to 3). Extends {@link Repository} rather than
 * {@code JpaRepository} so that {@code save()} cannot be called by accident.
 */
public interface FlashSaleItemRepository extends Repository<FlashSaleItem, Long> {

    Optional<FlashSaleItem> findByFlashSaleIdAndProductId(Long flashSaleId, Long productId);
}
