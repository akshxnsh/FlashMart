package com.flashmart.inventory.controller;

import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal only: called by Order over Feign, never routed through the Gateway.
 * It takes an order id with no ownership check and mutates stock; the
 * {@code /internal/} prefix is a convention, the Gateway's routes are the control.
 */
@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    // Every business outcome is 200; non-200s come only from ReservationExceptionHandler.
    @PostMapping("/internal/flash-sale-items/reserve")
    public ReserveResponse reserve(@Valid @RequestBody ReserveRequest request) {
        return reservationService.reserve(request);
    }
}
