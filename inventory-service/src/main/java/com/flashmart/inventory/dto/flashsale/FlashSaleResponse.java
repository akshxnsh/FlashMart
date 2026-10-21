package com.flashmart.inventory.dto.flashsale;

import com.flashmart.inventory.entity.FlashSale;
import com.flashmart.inventory.entity.FlashSaleStatus;

import java.time.Instant;

public record FlashSaleResponse(
        Long id,
        String name,
        Instant startTime,
        Instant endTime,
        FlashSaleState state
) {

    /**
     * {@code now} is a parameter so the mapping is testable at a fixed instant.
     *
     * <p>The state is for display only. Any decision that writes (reserve,
     * "frozen once open") evaluates liveness against the database clock in the
     * same statement, never against this {@code now}.
     */
    public static FlashSaleResponse from(FlashSale sale, Instant now) {
        return new FlashSaleResponse(
                sale.getId(),
                sale.getName(),
                sale.getStartTime(),
                sale.getEndTime(),
                stateOf(sale, now));
    }

    // Half-open window, the same as the liveness rule: start <= now < end.
    private static FlashSaleState stateOf(FlashSale sale, Instant now) {
        if (sale.getStatus() == FlashSaleStatus.CANCELLED) {
            return FlashSaleState.CANCELLED;
        }
        if (now.isBefore(sale.getStartTime())) {
            return FlashSaleState.SCHEDULED;
        }
        if (now.isBefore(sale.getEndTime())) {
            return FlashSaleState.LIVE;
        }
        return FlashSaleState.ENDED;
    }
}
