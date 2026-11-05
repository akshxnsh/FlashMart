package com.flashmart.paymentstub;

/** A created payment, with the request that created it (for the same-body check). */
public record Payment(String paymentRef, ValidRequest request, String status) {
}
