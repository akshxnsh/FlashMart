package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.FlashSaleItem;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Finders and the one stock write of {@code reserve}. No {@code @Lock}, no
 * retry (decisions.md, "Reserve write path"). Extends {@link Repository}
 * rather than {@code JpaRepository} so that {@code save()} cannot be called
 * by accident.
 */
public interface FlashSaleItemRepository extends Repository<FlashSaleItem, Long> {

    Optional<FlashSaleItem> findByFlashSaleIdAndProductId(Long flashSaleId, Long productId);

    Optional<FlashSaleItem> findById(Long id);

    /**
     * The whole stock check and decrement in ONE statement, so it is atomic
     * under READ COMMITTED: a blocked UPDATE re-checks this WHERE against the
     * newly committed row, so the loser of "two buyers, last unit" matches 0
     * rows and needs no retry.
     *
     * <ul>
     *   <li>{@code available_stock >= :qty}: the stock guard (the table's
     *       CHECK is only the last line of defence behind it).</li>
     *   <li>liveness: computed here, never stored, half-open, with the
     *       database clock in the same statement as the decrement.</li>
     *   <li>{@code version = version + 1}: a native UPDATE does not bump
     *       {@code @Version} by itself (CLAUDE.md raw SQL trap).</li>
     * </ul>
     *
     * @return 1 if the units were taken, 0 if stock is short or the sale is not live
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE flash_sale_items i
               SET available_stock = i.available_stock - :qty,
                   version = i.version + 1
              FROM flash_sales s
             WHERE i.id = :id
               AND s.id = i.flash_sale_id
               AND i.available_stock >= :qty
               AND s.status <> 'CANCELLED'
               AND s.start_time <= now()
               AND now() < s.end_time
            """)
    int decrementIfAvailableAndLive(@Param("id") Long id, @Param("qty") int qty);
}
