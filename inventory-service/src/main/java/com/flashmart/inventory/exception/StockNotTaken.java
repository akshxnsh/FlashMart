package com.flashmart.inventory.exception;

/**
 * The stock UPDATE matched 0 rows: stock is short or the sale is not live.
 * Unchecked on purpose: Spring rolls a transaction back only for unchecked
 * exceptions, and the rollback is what removes the reservation row inserted
 * just before the UPDATE. Internal to reserve; never reaches the controller.
 */
public class StockNotTaken extends RuntimeException {

    private final Long flashSaleId;

    public StockNotTaken(Long flashSaleId) {
        super("stock not taken for sale " + flashSaleId);
        this.flashSaleId = flashSaleId;
    }

    public Long getFlashSaleId() {
        return flashSaleId;
    }
}
