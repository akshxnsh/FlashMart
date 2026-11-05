package com.flashmart.paymentstub;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Payments by Idempotency-Key. In memory; gone on restart. */
@Component
public class PaymentStore {

    private final ConcurrentHashMap<String, Payment> payments = new ConcurrentHashMap<>();

    /**
     * Returns the payment already made for this key, or creates one. The
     * lookup and the create are one atomic step per key
     * ({@code computeIfAbsent}), so two concurrent requests with the same key
     * can never create two payments.
     */
    public Payment createIfAbsent(String key, ValidRequest request) {
        return payments.computeIfAbsent(key,
                k -> new Payment("pay_" + UUID.randomUUID(), request, "PENDING"));
    }

    public java.util.Optional<Payment> find(String key) {
        return java.util.Optional.ofNullable(payments.get(key));
    }

    public int size() {
        return payments.size();
    }

    public void clear() {
        payments.clear();
    }
}
