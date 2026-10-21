package com.flashmart.inventory.dto.reserve;

/**
 * Non-200 codes of {@code reserve}. Each one means something upstream is wrong
 * and should alert; none is an outcome a buyer can legitimately hit.
 */
public enum ErrorCode {
    INVALID_QUANTITY,
    INVALID_REQUEST,
    ITEM_NOT_FOUND,
    QUANTITY_MISMATCH
}
