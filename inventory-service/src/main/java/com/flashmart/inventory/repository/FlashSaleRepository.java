package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.FlashSale;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Finders plus the liveness read used only to explain a failed reserve. */
public interface FlashSaleRepository extends Repository<FlashSale, Long> {

    Optional<FlashSale> findById(Long id);

    /**
     * Same liveness rule as the stock UPDATE, on the database clock. Used only
     * after a 0-row UPDATE, to pick {@code SALE_NOT_LIVE} over
     * {@code OUT_OF_STOCK}. It never decides whether stock is taken.
     */
    @Query(nativeQuery = true, value = """
            SELECT (status <> 'CANCELLED' AND start_time <= now() AND now() < end_time)
              FROM flash_sales
             WHERE id = :id
            """)
    Boolean isLive(@Param("id") Long id);
}
