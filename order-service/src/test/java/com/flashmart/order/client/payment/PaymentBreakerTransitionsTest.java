package com.flashmart.order.client.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real breaker bean (built by {@link PaymentResilienceConfig} with
 * production's window 10, minimum 5, 50%, 3 half-open calls; only the open
 * time is shortened) through CLOSED -> OPEN -> HALF_OPEN -> CLOSED, and checks
 * each transition is logged at WARN. No Spring context, no network.
 */
class PaymentBreakerTransitionsTest {

    private static final long OPEN_MS = 150;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentResilienceConfig.class);
    private CircuitBreaker breaker;

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
        breaker = new PaymentResilienceConfig().paymentCircuitBreaker(10, 5, 50f, OPEN_MS, 3);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private static RetryableException timeout() {
        Request request = Request.create(Request.HttpMethod.POST, "http://localhost/x", Map.of(), null,
                new RequestTemplate());
        return new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Throwable) null, (Long) null,
                request);
    }

    private void failure() {
        breaker.acquirePermission();
        breaker.onError(1, TimeUnit.MILLISECONDS, timeout());
    }

    private void success() {
        breaker.acquirePermission();
        breaker.onSuccess(1, TimeUnit.MILLISECONDS);
    }

    private List<String> warns() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : logs.list) {
            if (e.getLevel() == Level.WARN) {
                out.add(e.getFormattedMessage());
            }
        }
        return out;
    }

    @Test
    void closedToOpenToHalfOpenToClosedIsLoggedAtWarnWithTheFailureRate() throws Exception {
        List<String> seen = new ArrayList<>();
        breaker.getEventPublisher().onStateTransition(e -> seen.add(
                e.getStateTransition().getFromState() + "->" + e.getStateTransition().getToState()));

        // Four failures: under the minimum of 5 calls, so nothing is judged yet.
        for (int i = 0; i < 4; i++) {
            failure();
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(seen.isEmpty());

        // The fifth call reaches the minimum: 5 of 5 failed (100% >= 50%) -> OPEN.
        failure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertEquals(List.of("CLOSED->OPEN"), seen);

        // OPEN becomes HALF_OPEN lazily: only when a call arrives after open-ms.
        Thread.sleep(OPEN_MS + 100);
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState()); // time alone changes nothing
        assertEquals(List.of("CLOSED->OPEN"), seen);
        success(); // the first call after the wait is the first half-open trial
        assertEquals(List.of("CLOSED->OPEN", "OPEN->HALF_OPEN"), seen);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        // Three successful trials in total close it.
        success();
        success();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(List.of("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->CLOSED"), seen);

        // Each transition is one WARN line carrying the rate it was decided on.
        List<String> warns = warns();
        assertEquals(3, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("CLOSED -> OPEN") && warns.get(0).contains("failure rate 100.0%")
                && warns.get(0).contains("5 failed"), warns.get(0));
        assertTrue(warns.get(1).contains("OPEN -> HALF_OPEN"), warns.get(1));
        assertTrue(warns.get(2).contains("HALF_OPEN -> CLOSED"), warns.get(2));
    }

    /**
     * Half-open is judged on its 3 trial calls with the same 50% threshold, not
     * "any failure reopens it": 2 failed trials of 3 (67%) reopen; 1 of 3 (33%)
     * would close.
     */
    @Test
    void halfOpenTrialsFailingAtOrAboveTheRateReopenTheBreakerAndIsLogged() throws Exception {
        for (int i = 0; i < 5; i++) {
            failure();
        }
        Thread.sleep(OPEN_MS + 100);
        failure();
        failure();
        success();

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        List<String> warns = warns();
        assertEquals(3, warns.size(), warns.toString());
        assertTrue(warns.get(2).contains("HALF_OPEN -> OPEN"), warns.get(2));
    }

    @Test
    void oneFailedTrialOfThreeStillClosesTheBreaker() throws Exception {
        for (int i = 0; i < 5; i++) {
            failure();
        }
        Thread.sleep(OPEN_MS + 100);
        failure();
        success();
        success();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }
}
