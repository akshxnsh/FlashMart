package com.flashmart.order.controller;

import io.swagger.v3.oas.annotations.Hidden;
import com.flashmart.order.entity.Order;
import com.flashmart.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/orders/{orderId}/status} → {@code {orderId, status}}, or
 * {@code 404} when Order has no such order (architecture.md §3, "Inventory →
 * Order"; decisions.md, "Day 9: Inventory stale-reservation reconciler").
 *
 * <p>Its only caller is Inventory's stale-reservation reconciler, which acts
 * only on a terminal answer. Read-only: one short read, no CAS, nothing
 * written. Internal: never routed through the Gateway (the Gateway's route
 * test checks no route matches {@code /internal/**}), and not under
 * {@code /api/orders}, so the Gateway-signature filter does not apply; like
 * Inventory's internal reserve it has no authentication of its own, and is
 * reachable only on the service's own port.
 */
/** Hidden from the OpenAPI docs: internal, called by Inventory only. */
@Hidden
@RestController
@RequiredArgsConstructor
public class InternalOrderStatusController {

    public record OrderStatusResponse(long orderId, String status) {
    }

    private final OrderRepository orders;

    @GetMapping("/internal/orders/{orderId}/status")
    public ResponseEntity<OrderStatusResponse> status(@PathVariable long orderId) {
        return orders.findById(orderId)
                .map(Order::getStatus)
                .map(status -> ResponseEntity.ok(new OrderStatusResponse(orderId, status.name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
