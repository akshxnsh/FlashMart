package com.flashmart.identity.auth.dto;

/**
 * {@code 200} body of login and refresh. {@code refreshToken} is the raw
 * token, shown once (only its hash is stored). {@code expiresIn} is the
 * ACCESS token's lifetime in seconds.
 */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
}
