package com.flashmart.order.client.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The payment circuit breaker and retry, built with resilience4j's core
 * libraries (no Boot starter). Every number comes from {@code payment.*}
 * (decisions.md, Day 5, points 3 and 4; PROVISIONAL).
 */
@Slf4j
@Configuration
public class PaymentResilienceConfig {

    /**
     * COUNT_BASED over the last {@code window-size} calls, judged once at least
     * {@code minimum-calls} are recorded. Timeouts, connect errors and 5xx are
     * failures; a 4xx is ignored (it says nothing about the gateway's health).
     * Open for {@code open-ms}, then {@code half-open-calls} trial calls decide.
     *
     * <p>Every state transition is logged at WARN with the failure rate at that
     * moment. Observability only: the breaker's behaviour is unchanged. Note
     * that OPEN becomes HALF_OPEN lazily (resilience4j's default): at the first
     * call after {@code open-ms}, not when the time passes, so that line appears
     * when a request arrives.
     */
    @Bean
    public CircuitBreaker paymentCircuitBreaker(@Value("${payment.breaker.window-size}") int windowSize,
                                                @Value("${payment.breaker.minimum-calls}") int minimumCalls,
                                                @Value("${payment.breaker.failure-rate-percent}") float failureRate,
                                                @Value("${payment.breaker.open-ms}") long openMs,
                                                @Value("${payment.breaker.half-open-calls}") int halfOpenCalls) {
        CircuitBreaker breaker = CircuitBreaker.of("payment-gateway", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofMillis(openMs))
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .recordException(PaymentCalls::isTransient)
                .ignoreException(PaymentCalls::isClientError)
                .build());
        breaker.getEventPublisher().onStateTransition(event -> log.warn(
                "payment circuit breaker {} -> {} (failure rate {}%, {} calls in window, {} failed)",
                event.getStateTransition().getFromState(), event.getStateTransition().getToState(),
                breaker.getMetrics().getFailureRate(), breaker.getMetrics().getNumberOfBufferedCalls(),
                breaker.getMetrics().getNumberOfFailedCalls()));
        return breaker;
    }

    /**
     * {@code attempts} in total (not retries), a fixed wait between them. Only a
     * transient failure is retried: never a 4xx, never CallNotPermittedException
     * (an open breaker means "do not call", so retrying it would only wait).
     */
    @Bean
    public Retry paymentRetry(@Value("${payment.attempts}") int attempts,
                              @Value("${payment.wait-ms}") long waitMs) {
        return Retry.of("payment-gateway", RetryConfig.custom()
                .maxAttempts(attempts)
                .waitDuration(Duration.ofMillis(waitMs))
                .retryOnException(PaymentCalls::isTransient)
                .build());
    }
}
