package com.flashmart.identity.auth.dto;

/** {@code 200} body of login. {@code expiresIn} is the access token's lifetime in seconds. */
public record TokenResponse(String accessToken, long expiresIn) {
}
