package com.flashmart.inventory.dto.reserve;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The HTTP 200 body of {@code reserve}. {@code outcome} is always present and
 * is the one field a client branches on.
 *
 * <p>The other four fields exist only when {@code outcome = RESERVED}, and are
 * omitted from the JSON (not written as {@code null}) otherwise. A replay
 * returns exactly the same shape as the first call; nothing marks it as one.
 *
 * <p>Build it through {@link #reserved} or {@link #notReserved} only, so a
 * {@code RESERVED} response without its fields cannot exist.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReserveResponse(
        ReserveOutcome outcome,
        Long reservationId,
        // Money crosses every boundary as a JSON string, never a number.
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salePrice,
        Long brandId,
        String productName
) {

    /**
     * Scale 2 makes {@code toString()} plain notation ("3999.00", never
     * "4E+3"). UNNECESSARY throws on a third decimal instead of rounding money
     * silently; a DECIMAL(12, 2) column can never produce one.
     */
    public static ReserveResponse reserved(Long reservationId, BigDecimal salePrice,
                                           Long brandId, String productName) {
        return new ReserveResponse(
                ReserveOutcome.RESERVED,
                Objects.requireNonNull(reservationId, "reservationId"),
                Objects.requireNonNull(salePrice, "salePrice").setScale(2, RoundingMode.UNNECESSARY),
                Objects.requireNonNull(brandId, "brandId"),
                Objects.requireNonNull(productName, "productName"));
    }

    public static ReserveResponse notReserved(ReserveOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ReserveOutcome.RESERVED) {
            throw new IllegalArgumentException("RESERVED needs its fields; use reserved(...)");
        }
        return new ReserveResponse(outcome, null, null, null, null);
    }
}
