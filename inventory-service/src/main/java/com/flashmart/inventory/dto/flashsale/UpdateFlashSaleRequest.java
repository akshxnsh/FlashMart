package com.flashmart.inventory.dto.flashsale;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Full replacement of a sale's editable terms; every field is required.
 * Whether the sale may still be edited at all ("frozen once it opens") is a
 * service-layer check against the database clock, not something a DTO can see.
 */
public record UpdateFlashSaleRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Instant startTime,
        @NotNull Instant endTime
) {

    /** Same rule as {@link CreateFlashSaleRequest#isWindowOrdered()}. */
    @JsonIgnore
    @AssertTrue(message = "endTime must be after startTime")
    public boolean isWindowOrdered() {
        return startTime == null || endTime == null || endTime.isAfter(startTime);
    }
}
