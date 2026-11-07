package com.flashmart.identity.auth.dto;

public enum ErrorCode {
    /** 400 for a bad field or unreadable body; 415 for a body that is not JSON. */
    INVALID_REQUEST,
    /** 409: the email belongs to another user. */
    EMAIL_TAKEN
}
