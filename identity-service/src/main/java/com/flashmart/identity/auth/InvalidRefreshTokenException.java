package com.flashmart.identity.auth;

/** Unknown, expired, or reused refresh token: the same 401, so the response does not say which. */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("invalid refresh token");
    }
}
