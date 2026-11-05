package com.flashmart.order.client.payment;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * The one place Order starts a payment (decisions.md, "Day 5: payment step,
 * resilience and derived deadlines", points 2 to 4).
 *
 * <p>Retry wraps the breaker (retry OUTSIDE): every attempt asks the breaker
 * first, so once it opens, the next attempt is refused at once and is not
 * retried. Worst case = attempts x (connect + read) + waits, the payment budget
 * the CREATED deadline is built from.
 *
 * <p>The Idempotency-Key is {@code "order-" + orderId}: derived, never
 * random, so every attempt, every retried request and every resume after a
 * crash sends the same key, and a gateway that honours it (RZP-1: designed,
 * not verified) returns the same payment instead of a second one.
 *
 * <p>No transaction may be open when this is called; the caller guarantees it.
 */
@Component
public class PaymentCalls {

    static final String CURRENCY = "INR";

    private final PaymentGatewayClient client;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public PaymentCalls(PaymentGatewayClient client, CircuitBreaker paymentCircuitBreaker, Retry paymentRetry) {
        this.client = client;
        this.breaker = paymentCircuitBreaker;
        this.retry = paymentRetry;
    }

    public static String idempotencyKey(long orderId) {
        return "order-" + orderId;
    }

    /**
     * @throws PaymentUnavailableException retries exhausted, breaker open, or an unusable 2xx
     * @throws PaymentRejectedException    the gateway answered 4xx
     */
    public InitiatePaymentResponse initiate(long orderId, BigDecimal amount) {
        String key = idempotencyKey(orderId);
        InitiatePaymentRequest body = new InitiatePaymentRequest(orderId, amount, CURRENCY);
        Supplier<InitiatePaymentResponse> call = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(breaker, () -> client.initiate(key, body)));
        InitiatePaymentResponse response;
        try {
            response = call.get();
        } catch (CallNotPermittedException e) {
            throw new PaymentUnavailableException("payment circuit breaker is open", e);
        } catch (FeignException e) {
            if (isClientError(e)) {
                throw new PaymentRejectedException("payment gateway answered " + e.status() + ": "
                        + e.contentUTF8(), e);
            }
            throw new PaymentUnavailableException(isTransient(e)
                    ? "payment gateway unavailable after " + retry.getRetryConfig().getMaxAttempts()
                            + " attempts: " + e.getMessage()
                    : "payment gateway gave an unusable answer: " + e.getMessage(), e);
        }
        if (response == null || response.paymentRef() == null || response.paymentRef().isBlank()) {
            throw new PaymentUnavailableException("payment gateway answered without a paymentRef", null);
        }
        return response;
    }

    /** Retried and counted by the breaker: a timeout, a connect error (both RetryableException) or a 5xx. */
    static boolean isTransient(Throwable t) {
        return t instanceof RetryableException || (t instanceof FeignException f && f.status() >= 500);
    }

    /** Never retried, ignored by the breaker: a 4xx. */
    static boolean isClientError(Throwable t) {
        return t instanceof FeignException f && !(t instanceof RetryableException)
                && f.status() >= 400 && f.status() < 500;
    }
}
