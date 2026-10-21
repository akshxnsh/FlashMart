package com.flashmart.inventory.dto.reserve;

/**
 * Every outcome a buyer can legitimately hit. All of them are HTTP 200: a
 * non-2xx for a business outcome would count as a failure in Order's circuit
 * breaker and could open it in the middle of a drop.
 */
public enum ReserveOutcome {
    RESERVED,
    OUT_OF_STOCK,
    SALE_NOT_LIVE,
    RESERVATION_RELEASED
}
