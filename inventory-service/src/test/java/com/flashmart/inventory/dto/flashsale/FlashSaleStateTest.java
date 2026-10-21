package com.flashmart.inventory.dto.flashsale;

import com.flashmart.inventory.entity.FlashSale;
import com.flashmart.inventory.entity.FlashSaleStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The computed state at a fixed {@code now}, on both edges of the half-open window. */
class FlashSaleStateTest {

    private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant END = Instant.parse("2030-01-01T12:00:00Z");

    private static FlashSaleState stateAt(FlashSaleStatus status, Instant now) {
        FlashSale sale = new FlashSale(1L, 3L, "Drop", START, END, status);
        return FlashSaleResponse.from(sale, now).state();
    }

    @Test
    void beforeStartIsScheduled() {
        assertEquals(FlashSaleState.SCHEDULED, stateAt(FlashSaleStatus.ACTIVE, START.minusNanos(1000)));
    }

    @Test
    void exactlyAtStartIsLive() {
        assertEquals(FlashSaleState.LIVE, stateAt(FlashSaleStatus.ACTIVE, START));
    }

    @Test
    void justBeforeEndIsLive() {
        assertEquals(FlashSaleState.LIVE, stateAt(FlashSaleStatus.ACTIVE, END.minusNanos(1000)));
    }

    @Test
    void exactlyAtEndIsEnded() {
        assertEquals(FlashSaleState.ENDED, stateAt(FlashSaleStatus.ACTIVE, END));
    }

    @Test
    void afterEndIsEnded() {
        assertEquals(FlashSaleState.ENDED, stateAt(FlashSaleStatus.ACTIVE, END.plusSeconds(60)));
    }

    @Test
    void cancelledWinsEvenInsideTheWindow() {
        assertEquals(FlashSaleState.CANCELLED, stateAt(FlashSaleStatus.CANCELLED, START.plusSeconds(60)));
    }
}
