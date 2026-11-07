package com.flashmart.identity.auth;

public class EmailTakenException extends RuntimeException {

    public EmailTakenException() {
        super("email already taken");
    }
}
