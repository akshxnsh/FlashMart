package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.FlashSale;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /**
     * Browse page (decisions.md, "Day 8", point 8): not cancelled and not yet
     * ended, ordered by {@code (start_time, id)} so the order is total and a
     * page boundary never reorders. The caller passes size + 1 as
     * {@code limit} to learn whether another page exists. {@code now()} is the
     * database clock, like reserve liveness rule, never the app clock.
     */
    @Query(nativeQuery = true, value = """
            SELECT * FROM flash_sales
             WHERE status <> 'CANCELLED' AND end_time > now()
             ORDER BY start_time, id
             LIMIT :limit OFFSET :offset
            """)
    List<FlashSale> findBrowsePage(@Param("limit") int limit, @Param("offset") long offset);

    /** The database clock; inside one transaction it equals the {@code now()} of the page query. */
    @Query(nativeQuery = true, value = "SELECT now()")
    Instant databaseNow();
}
