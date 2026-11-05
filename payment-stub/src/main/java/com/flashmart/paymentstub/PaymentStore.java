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
        return createOrFind(key, request).payment();
    }

    /** The payment and whether THIS call created it (so a replay never triggers a second auto-webhook). */
    public record Result(Payment payment, boolean created) {
    }

    public Result createOrFind(String key, ValidRequest request) {
        java.util.concurrent.atomic.AtomicBoolean created = new java.util.concurrent.atomic.AtomicBoolean();
        Payment payment = payments.computeIfAbsent(key, k -> {
            created.set(true);
            return new Payment("pay_" + UUID.randomUUID(), request, "PENDING");
        });
        return new Result(payment, created.get());
    }

    /** A stored payment by its ref, for /admin/webhook defaults. */
    public java.util.Optional<Payment> findByRef(String paymentRef) {
        return payments.values().stream().filter(p -> p.paymentRef().equals(paymentRef)).findFirst();
    }

    /** A stored payment for this order id (any one, if several), for /admin/webhook defaults. */
    public java.util.Optional<Payment> findByOrderId(long orderId) {
        return payments.values().stream().filter(p -> p.request().orderId() == orderId).findFirst();
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
