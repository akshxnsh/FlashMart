package com.flashmart.identity.auth.dto;

public enum ErrorCode {
    /** 400 for a bad field or unreadable body; 415 for a body that is not JSON. */
    INVALID_REQUEST,
    /** 409: the email belongs to another user. */
    EMAIL_TAKEN,
    /** 401: unknown email or wrong password, deliberately indistinguishable. */
    INVALID_CREDENTIALS,
    /** 401: the refresh token is unknown, expired, or was already used (reuse also revokes all the user's tokens). */
    INVALID_REFRESH_TOKEN
}
