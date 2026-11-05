package com.flashmart.inventory.messaging;

/**
 * The message can never be processed, however often it is retried (broken
 * JSON, no usable {@code order_id}). The error handler skips it at once
 * instead of retrying (decisions.md, "Day 7", point 4).
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedEventException(String message) {
        super(message);
    }
}
