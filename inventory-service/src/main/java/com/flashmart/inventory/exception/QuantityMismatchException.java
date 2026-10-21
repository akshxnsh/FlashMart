package com.flashmart.inventory.exception;

/**
 * A reservation already exists for this order and item with a different
 * quantity. Not a retry: order_items fixes quantity at creation, so the same
 * order cannot legitimately ask twice for different amounts. Mapped to 409.
 */
public class QuantityMismatchException extends RuntimeException {

    public QuantityMismatchException(Long orderId, int heldQuantity, int requestedQuantity) {
        super("Order " + orderId + " holds " + heldQuantity + " units, request asked for " + requestedQuantity);
    }
}
