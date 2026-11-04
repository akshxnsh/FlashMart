package com.flashmart.inventory.service;

import com.flashmart.inventory.dto.reserve.ReserveOutcome;
import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.repository.FlashSaleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catch in {@link ReservationServiceImpl#reserve}, with no database: the
 * transaction half is a mock that throws what Postgres would have thrown.
 * A real unique violation is exercised by {@link ReservationConcurrencyTest}
 * only when the race actually lands there.
 */
class ReservationOrchestratorTest {

    private final ReservationTransaction transaction = mock(ReservationTransaction.class);
    private final FlashSaleRepository sales = mock(FlashSaleRepository.class);
    private final ReservationServiceImpl service = new ReservationServiceImpl(transaction, sales);

    private final ReserveRequest request = new ReserveRequest(1L, 2L, 3L, 2);

    @Test
    void uniqueViolationAnswersFromTheReplayTheOtherCopyStored() {
        ReserveResponse stored = ReserveResponse.reserved(77L, new BigDecimal("3999.00"), 9001L, "Shoe");
        when(transaction.attempt(request)).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(transaction.findReplay(request)).thenReturn(Optional.of(stored));

        ReserveResponse response = service.reserve(request);

        assertEquals(stored, response);
        assertEquals(ReserveOutcome.RESERVED, response.outcome());
    }

    @Test
    void uniqueViolationWithNoReplayRethrowsTheOriginalException() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("some other constraint");
        when(transaction.attempt(request)).thenThrow(original);
        when(transaction.findReplay(request)).thenReturn(Optional.empty());

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class, () -> service.reserve(request));

        assertSame(original, thrown);
        verify(transaction).findReplay(any());
    }
}
