package com.flashmart.order.client.inventory;

/** Every 200 outcome of Inventory's reserve (Inventory's {@code ReserveOutcome}). */
public enum ReserveOutcome {
    RESERVED,
    OUT_OF_STOCK,
    SALE_NOT_LIVE,
    RESERVATION_RELEASED,
    ITEM_NOT_IN_SALE
}
