package com.flashmart.inventory.dto.flashsale;

/**
 * A sale's state as shown to a reader. Computed at read time from status and
 * the two timestamps; never stored (only CANCELLED is a stored fact).
 */
public enum FlashSaleState {
    SCHEDULED,
    LIVE,
    CANCELLED,
    ENDED
}
