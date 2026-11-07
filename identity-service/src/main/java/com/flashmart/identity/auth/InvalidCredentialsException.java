package com.flashmart.identity.auth;

/** Unknown email or wrong password: deliberately the same exception, so the same 401. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid email or password");
    }
}
