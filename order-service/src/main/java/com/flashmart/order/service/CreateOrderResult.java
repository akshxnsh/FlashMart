package com.flashmart.order.service;

import com.flashmart.order.dto.CreateOrderResponse;

/** A successful create: {@code created} is true for a new order (201), false for a replay (200). */
public record CreateOrderResult(boolean created, CreateOrderResponse body) {
}
