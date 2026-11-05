package com.flashmart.paymentstub;

/** Error body: {@code { code, message }}. */
public record ErrorResponse(String code, String message) {
}
