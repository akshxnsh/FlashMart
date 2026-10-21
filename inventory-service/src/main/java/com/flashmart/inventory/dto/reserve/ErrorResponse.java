package com.flashmart.inventory.dto.reserve;

/**
 * Body of every non-200 {@code reserve} response. Callers branch on
 * {@code code}; {@code message} is for logs only and carries no contract.
 */
public record ErrorResponse(ErrorCode code, String message) {
}
