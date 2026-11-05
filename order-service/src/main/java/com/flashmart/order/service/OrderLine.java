package com.flashmart.order.service;

/** One merged line of an order: a product appears at most once per order. */
public record OrderLine(Long productId, int quantity) {
}
