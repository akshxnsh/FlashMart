package com.flashmart.paymentstub;

/**
 * One webhook, as Order's contract has it: {@code {order_id, payment_ref,
 * status, amount}} with the amount a decimal string (decisions.md, "Day 6", point 4).
 */
public record WebhookMessage(long orderId, String paymentRef, String status, String amount) {

    public static WebhookMessage of(Payment payment, String status) {
        return new WebhookMessage(payment.request().orderId(), payment.paymentRef(), status,
                payment.request().amount().toPlainString());
    }
}
