package com.flashmart.order.client.inventory;

/**
 * Inventory gave no usable answer: timeout, refused connection, or any non-200.
 * The message is the cause text that goes into {@code orders.failure_detail}
 * (debug only).
 */
public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
