package com.flashmart.order.client.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real Feign client with the real resilience4j retry and breaker, over
 * real HTTP, against a JDK {@link HttpServer} STUB playing the gateway. Tests
 * Order's side of the wire only: NOT the real payment-stub, NOT a real gateway.
 *
 * <p>Shortened here so the suite is quick (same properties as production):
 * read timeout 300 ms, wait 50 ms, breaker open 500 ms. Attempts (2) and the
 * breaker's window (10), minimum (5), rate (50%) and half-open calls (3) are
 * production's values.
 */
@SpringBootTest
class PaymentCallsHttpTest {

    private static final int READ_TIMEOUT_MS = 300;
    private static final int OPEN_MS = 500;
    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();
    private static final List<String> KEYS = new CopyOnWriteArrayList<>();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = start();

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange, int call) throws IOException;
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                KEYS.add(String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                HANDLER.get().handle(exchange, KEYS.size());
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        registry.add("payment-gateway.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("payment.read-timeout-ms", () -> READ_TIMEOUT_MS);
        registry.add("payment.wait-ms", () -> 50);
        registry.add("payment.breaker.open-ms", () -> OPEN_MS);
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Autowired
    private PaymentCalls payments;

    @Autowired
    private CircuitBreaker breaker;

    @BeforeEach
    void reset() {
        KEYS.clear();
        BODIES.clear();
        breaker.reset();
    }

    static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static void accepted(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        respond(exchange, 202, "{\"paymentRef\":\"pay_" + key + "\",\"orderId\":42,\"amount\":\"59.00\","
                + "\"status\":\"PENDING\"}");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private InitiatePaymentResponse initiate() {
        return payments.initiate(42L, new BigDecimal("59.00"));
    }

    @Test
    void aSuccessSendsTheDerivedKeyAndTheAmountAsAString() {
        HANDLER.set((exchange, call) -> accepted(exchange));

        InitiatePaymentResponse response = initiate();

        assertEquals("pay_order-42", response.paymentRef());
        assertEquals(List.of("order-42"), KEYS);
        assertTrue(BODIES.getFirst().contains("\"amount\":\"59.00\""), BODIES.getFirst()); // a string, not 59.00
        assertTrue(BODIES.getFirst().contains("\"orderId\":42") && BODIES.getFirst().contains("\"currency\":\"INR\""),
                BODIES.getFirst());
    }

    @Test
    void aTimeoutIsRetriedWithTheSameKeyAndTheSecondAttemptSucceeds() {
        HANDLER.set((exchange, call) -> {
            if (call == 1) {
                sleep(READ_TIMEOUT_MS * 4L);
            }
            accepted(exchange);
        });

        InitiatePaymentResponse response = initiate();

        assertEquals("pay_order-42", response.paymentRef());
        assertEquals(List.of("order-42", "order-42"), KEYS); // the key is never regenerated
    }

    @Test
    void a5xxIsRetriedOnceThenUnavailable() {
        HANDLER.set((exchange, call) -> respond(exchange, 500, "{\"code\":\"STUB_ERROR\"}"));

        assertThrows(PaymentUnavailableException.class, this::initiate);

        assertEquals(List.of("order-42", "order-42"), KEYS); // 2 attempts in total
    }

    @Test
    void a4xxIsNeverRetriedAndIsRejected() {
        HANDLER.set((exchange, call) -> respond(exchange, 400, "{\"code\":\"PAYMENT_REJECTED\"}"));

        PaymentRejectedException e = assertThrows(PaymentRejectedException.class, this::initiate);

        assertEquals(1, KEYS.size());
        assertTrue(e.getMessage().contains("400") && e.getMessage().contains("PAYMENT_REJECTED"), e.getMessage());
    }

    @Test
    void a4xxDoesNotCountTowardsOpeningTheBreaker() {
        HANDLER.set((exchange, call) -> respond(exchange, 400, "{}"));

        for (int i = 0; i < 8; i++) {
            assertThrows(PaymentRejectedException.class, this::initiate);
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(8, KEYS.size());
    }

    @Test
    void theBreakerOpensAfterFiveFailedCallsThenRefusesWithoutCallingAndHalfOpenRecovers() {
        HANDLER.set((exchange, call) -> respond(exchange, 500, "{}"));

        // Requests 1 and 2: two attempts each = 4 recorded failures, under the minimum of 5.
        assertThrows(PaymentUnavailableException.class, this::initiate);
        assertThrows(PaymentUnavailableException.class, this::initiate);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        // Request 3: its first attempt is the 5th failure (100% >= 50%), so the
        // breaker opens and the retry's second attempt is refused, not sent.
        assertThrows(PaymentUnavailableException.class, this::initiate);
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertEquals(5, KEYS.size());

        // Open: refused at once, the stub is never called, and it is not retried.
        long start = System.nanoTime();
        PaymentUnavailableException open = assertThrows(PaymentUnavailableException.class, this::initiate);
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(5, KEYS.size());
        assertTrue(open.getMessage().contains("open"), open.getMessage());
        assertTrue(tookMs < 40, "an open breaker should fail at once, took " + tookMs + " ms"); // < the 50 ms wait

        // After open-ms, three successful trial calls close it again.
        sleep(OPEN_MS + 100);
        HANDLER.set((exchange, call) -> accepted(exchange));
        for (int i = 0; i < 3; i++) {
            assertEquals("pay_order-42", initiate().paymentRef());
            assertTrue(breaker.getState() != CircuitBreaker.State.OPEN);
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(8, KEYS.size());
    }

    @Test
    void anAcceptedAnswerWithoutAPaymentRefIsUnavailable() {
        HANDLER.set((exchange, call) -> respond(exchange, 202, "{\"status\":\"PENDING\"}"));

        assertThrows(PaymentUnavailableException.class, this::initiate);
        assertEquals(1, KEYS.size());
    }
}
