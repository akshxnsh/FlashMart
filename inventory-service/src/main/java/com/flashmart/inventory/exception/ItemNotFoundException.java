package com.flashmart.inventory.exception;

/**
 * No flash_sale_items row for {@code (flashSaleId, productId)}. A bug upstream,
 * not a buyer outcome: Order validates every item against the order's sale at
 * creation, so by reserve time the row must exist. Mapped to 404.
 */
public class ItemNotFoundException extends RuntimeException {

    public ItemNotFoundException(Long flashSaleId, Long productId) {
        super("No sale item for flashSaleId=" + flashSaleId + ", productId=" + productId);
    }
}
