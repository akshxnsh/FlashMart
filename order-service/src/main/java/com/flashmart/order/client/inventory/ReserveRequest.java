package com.flashmart.order.client.inventory;

/**
 * Order's own copy of Inventory's reserve request. Not shared code: each
 * service owns its side of the contract. Inventory's idempotency key is
 * {@code (orderId, flashSaleId, productId)}, so a retried or resumed loop
 * sending the same request gets the same reservation back.
 */
public record ReserveRequest(Long orderId, Long flashSaleId, Long productId, Integer quantity) {
}
