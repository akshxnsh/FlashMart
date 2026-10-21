package com.flashmart.inventory.service;

import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.exception.QuantityMismatchException;

/**
 * Reserves stock for one order item. Contract only: no implementation exists,
 * because the write path, locking strategy and isolation level are open
 * decisions (architecture.md §9).
 *
 * <p>Evaluation order is part of the contract:
 * <ol>
 *   <li>Validate quantity (done before this is called, by the controller).</li>
 *   <li>Resolve {@code (flashSaleId, productId)} to a sale item, else
 *       return {@code ITEM_NOT_IN_SALE} and log a WARN each time (a plain log
 *       line until the observability phase brings metrics).</li>
 *   <li>Replay: find an existing reservation for {@code (orderId, item)} in
 *       any status. If found: a different quantity throws
 *       {@link QuantityMismatchException}; a RELEASED row returns
 *       {@code RESERVATION_RELEASED} and never falls through to a new
 *       reservation; otherwise the stored reservation is returned as
 *       {@code RESERVED}. Liveness and stock are not checked on replay.</li>
 *   <li>Check liveness against the database clock: {@code SALE_NOT_LIVE}.</li>
 *   <li>Check and decrement stock: {@code OUT_OF_STOCK} or {@code RESERVED}.</li>
 * </ol>
 *
 * <p>When two first calls for the same {@code (orderId, item)} race, the loser
 * must end on the replay path, never with an error, and its stock change must
 * not survive. The mechanism depends on the open write-path decision.
 *
 * <p>Business outcomes are returned, never thrown; only
 * {@link QuantityMismatchException} leaves this method on purpose.
 */
public interface ReservationService {

    ReserveResponse reserve(ReserveRequest request);
}
