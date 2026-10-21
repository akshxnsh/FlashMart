package com.flashmart.inventory.dto.flashsale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * No brandId (it comes from the authenticated principal) and no status (a new
 * sale is ACTIVE; SCHEDULED/LIVE/ENDED are computed, never stored).
 */
public record CreateFlashSaleRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Instant startTime,
        @NotNull Instant endTime
) {

    /**
     * Strict {@code end > start}, the same rule as ck_flash_sales_window_ordered
     * (V7), which stays as the backstop. A null time passes here so that
     * {@code @NotNull} reports it instead of a misleading window error.
     */
    @JsonIgnore
    @AssertTrue(message = "endTime must be after startTime")
    public boolean isWindowOrdered() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}
