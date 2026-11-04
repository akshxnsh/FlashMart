package com.flashmart.inventory.service;

import com.flashmart.inventory.dto.reserve.ReserveOutcome;
import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.entity.FlashSaleItem;
import com.flashmart.inventory.entity.StockReservation;
import com.flashmart.inventory.entity.StockReservationStatus;
import com.flashmart.inventory.exception.QuantityMismatchException;
import com.flashmart.inventory.exception.StockNotTaken;
import com.flashmart.inventory.repository.FlashSaleItemRepository;
import com.flashmart.inventory.repository.FlashSaleRepository;
import com.flashmart.inventory.repository.ProductRepository;
import com.flashmart.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The transactional half of reserve. It is a separate bean so that
 * {@link ReservationServiceImpl} calls it through the Spring proxy and can
 * catch what it throws AFTER the transaction has rolled back.
 *
 * <p>One short transaction, no network call inside it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ReservationTransaction {

    private final FlashSaleItemRepository items;
    private final FlashSaleRepository sales;
    private final ProductRepository products;
    private final StockReservationRepository reservations;

    /**
     * Order of work is contractual: resolve item, replay check, insert the
     * reservation, stock UPDATE last. The UPDATE goes last so the row lock on
     * the hot stock row is held for the shortest time. The two immutable reads
     * (brand, product name) are done before the insert for the same reason.
     *
     * @throws StockNotTaken the UPDATE matched 0 rows; rolls back the insert
     * @throws QuantityMismatchException replay with a different quantity
     */
    @Transactional
    public ReserveResponse attempt(ReserveRequest request) {
        Optional<FlashSaleItem> found = items.findByFlashSaleIdAndProductId(
                request.flashSaleId(), request.productId());
        if (found.isEmpty()) {
            return itemNotInSale(request);
        }
        FlashSaleItem item = found.get();

        Optional<StockReservation> existing = reservations.findByOrderIdAndFlashSaleItemId(
                request.orderId(), item.getId());
        if (existing.isPresent()) {
            return replay(existing.get(), item, request);
        }

        ReserveDetails details = detailsFor(item);

        StockReservation reservation = new StockReservation();
        reservation.setOrderId(request.orderId());
        reservation.setFlashSaleItemId(item.getId());
        reservation.setQuantity(request.quantity());
        reservation.setStatus(StockReservationStatus.RESERVED);
        reservations.save(reservation);

        int rows = items.decrementIfAvailableAndLive(item.getId(), request.quantity());
        if (rows == 0) {
            throw new StockNotTaken(item.getFlashSaleId());
        }
        return ReserveResponse.reserved(
                reservation.getId(), item.getSalePrice(), details.brandId(), details.productName());
    }

    /**
     * Fresh-transaction re-read after a unique violation on the insert: the
     * other copy of this request committed first, so its reservation is the
     * answer. Empty means there is no such reservation, and the caller must
     * not guess.
     */
    @Transactional(readOnly = true)
    public Optional<ReserveResponse> findReplay(ReserveRequest request) {
        return items.findByFlashSaleIdAndProductId(request.flashSaleId(), request.productId())
                .flatMap(item -> reservations
                        .findByOrderIdAndFlashSaleItemId(request.orderId(), item.getId())
                        .map(existing -> replay(existing, item, request)));
    }

    // Replay never looks at liveness or stock: its units are already held.
    // A CONFIRMED reservation also answers as RESERVED: the buyer holds it.
    private ReserveResponse replay(StockReservation existing, FlashSaleItem item, ReserveRequest request) {
        if (!existing.getQuantity().equals(request.quantity())) {
            throw new QuantityMismatchException(request.orderId(), existing.getQuantity(), request.quantity());
        }
        if (existing.getStatus() == StockReservationStatus.RELEASED) {
            return ReserveResponse.notReserved(ReserveOutcome.RESERVATION_RELEASED);
        }
        ReserveDetails details = detailsFor(item);
        return ReserveResponse.reserved(
                existing.getId(), item.getSalePrice(), details.brandId(), details.productName());
    }

    private ReserveResponse itemNotInSale(ReserveRequest request) {
        // A plain log line until the observability phase brings metrics.
        log.warn("reserve: ITEM_NOT_IN_SALE orderId={} flashSaleId={} productId={}",
                request.orderId(), request.flashSaleId(), request.productId());
        return ReserveResponse.notReserved(ReserveOutcome.ITEM_NOT_IN_SALE);
    }

    private ReserveDetails detailsFor(FlashSaleItem item) {
        Long brandId = sales.findById(item.getFlashSaleId())
                .orElseThrow(() -> new IllegalStateException("sale missing for item " + item.getId()))
                .getBrandId();
        String productName = products.findById(item.getProductId())
                .orElseThrow(() -> new IllegalStateException("product missing for item " + item.getId()))
                .getName();
        return new ReserveDetails(brandId, productName);
    }

    private record ReserveDetails(Long brandId, String productName) {
    }
}
