package com.flashmart.identity.auth.dto;

/** {@code 201} body. Never carries the password hash. */
public record SignupResponse(long id, String name, String email) {
}
