package com.flashmart.inventory.service;

import com.flashmart.inventory.dto.reserve.ReserveOutcome;
import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.exception.StockNotTaken;
import com.flashmart.inventory.repository.FlashSaleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT transactional. It runs {@link ReservationTransaction} and
 * handles the two failures that only make sense after that transaction has
 * rolled back, which is why they cannot be handled inside it.
 *
 * <p>Quantity (1..5) is already validated before this is called: see
 * {@link ReserveRequest} and {@code @Valid} on the controller.
 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private final ReservationTransaction transaction;
    private final FlashSaleRepository sales;

    @Override
    public ReserveResponse reserve(ReserveRequest request) {
        try {
            return transaction.attempt(request);
        } catch (StockNotTaken notTaken) {
            // The insert is already rolled back. Not live first, else out of stock.
            return ReserveResponse.notReserved(classify(notTaken.getFlashSaleId()));
        } catch (DataIntegrityViolationException violation) {
            // Two copies of the same request raced; the other one committed.
            // Answer from what it stored. If nothing is there this was some other
            // constraint, so rethrow rather than guess.
            return transaction.findReplay(request).orElseThrow(() -> violation);
        }
    }

    private ReserveOutcome classify(Long flashSaleId) {
        return Boolean.TRUE.equals(sales.isLive(flashSaleId))
                ? ReserveOutcome.OUT_OF_STOCK
                : ReserveOutcome.SALE_NOT_LIVE;
    }
}
