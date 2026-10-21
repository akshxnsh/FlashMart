package com.flashmart.inventory.service;

import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import org.springframework.stereotype.Service;

/**
 * Placeholder so the application can start: {@link
 * com.flashmart.inventory.controller.ReservationController} needs a bean, and
 * reserve's logic waits on the open write-path, locking and isolation
 * decisions. Holds no logic on purpose. Every call fails loudly as a 500
 * (the exception handler does not map this exception), never as a business
 * outcome. Delete it when the real implementation lands.
 */
@Service
public class NotImplementedReservationService implements ReservationService {

    @Override
    public ReserveResponse reserve(ReserveRequest request) {
        throw new UnsupportedOperationException("reserve is not implemented yet");
    }
}
