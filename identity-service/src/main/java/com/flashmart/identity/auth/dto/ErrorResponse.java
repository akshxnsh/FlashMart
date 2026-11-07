package com.flashmart.identity.auth.dto;

/** Every auth error body: {@code { code, message }}, same contract as Inventory and Order. {@code message} is for logs only. */
public record ErrorResponse(ErrorCode code, String message) {
}
