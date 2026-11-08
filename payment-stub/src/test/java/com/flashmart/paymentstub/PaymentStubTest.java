package com.flashmart.paymentstub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stub over real HTTP on a random port, with real threads. Nothing here
 * says anything about a real gateway: only that this stub does what it says.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentStubTest {

    private record Reply(int status, String body) {
        String paymentRef() {
            Matcher m = Pattern.compile("\"paymentRef\":\"(pay_[^\"]+)\"").matcher(body);
            return m.find() ? m.group(1) : null;
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private PaymentStore store;

    @Autowired
    private ModeState modes;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void reset() {
        store.clear();
        modes.set(Mode.HEALTHY, 0);
    }

    private static String body(Object orderId, String amountJson, String currencyJson) {
        return "{\"orderId\":" + orderId + ",\"amount\":" + amountJson + ",\"currency\":" + currencyJson + "}";
    }

    private static String valid(long orderId, String amount) {
        return body(orderId, "\"" + amount + "\"", "\"INR\"");
    }

    private Reply post(String path, String key, String json, Duration timeout) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply initiate(String key, String json) throws Exception {
        return post("/api/payments/initiate", key, json, Duration.ofSeconds(10));
    }

    private void mode(String mode, long delayMs) throws Exception {
        Reply r = post("/admin/mode", null, "{\"mode\":\"" + mode + "\",\"delayMs\":" + delayMs + "}",
                Duration.ofSeconds(10));
        assertEquals(200, r.status(), r.body());
    }

    // ---- success and idempotent replay ----

    @Test
    void aValidInitiateIs202WithAPendingPayment() throws Exception {
        Reply r = initiate("k1", valid(42, "499.00"));

        assertEquals(202, r.status(), r.body());
        assertTrue(r.paymentRef() != null && r.paymentRef().startsWith("pay_"), r.body());
        assertTrue(r.body().contains("\"orderId\":42"), r.body());
        assertTrue(r.body().contains("\"amount\":\"499.00\""), r.body()); // a string, not a number
        assertTrue(r.body().contains("\"status\":\"PENDING\""), r.body());
    }

    @Test
    void theSameKeyAndBodyIsTheSamePayment() throws Exception {
        Reply first = initiate("k1", valid(42, "499.00"));
        Reply again = initiate("k1", valid(42, "499.00"));
        Reply sameValueOtherSpelling = initiate("k1", valid(42, "499"));

        assertEquals(202, again.status());
        assertEquals(first.paymentRef(), again.paymentRef());
        assertEquals(202, sameValueOtherSpelling.status());
        assertEquals(first.paymentRef(), sameValueOtherSpelling.paymentRef());
        assertEquals(1, store.size());
    }

    @Test
    void theSameKeyWithADifferentBodyIs400KeyReused() throws Exception {
        Reply first = initiate("k1", valid(42, "499.00"));

        Reply otherAmount = initiate("k1", valid(42, "500.00"));
        Reply otherOrder = initiate("k1", valid(43, "499.00"));

        assertEquals(400, otherAmount.status());
        assertTrue(otherAmount.body().contains("IDEMPOTENCY_KEY_REUSED"), otherAmount.body());
        assertEquals(400, otherOrder.status());
        assertTrue(otherOrder.body().contains("IDEMPOTENCY_KEY_REUSED"), otherOrder.body());
        assertEquals(1, store.size());
        assertEquals(first.paymentRef(), store.find("k1").orElseThrow().paymentRef()); // the original is untouched
    }

    @Test
    void differentKeysAreDifferentPayments() throws Exception {
        Reply a = initiate("k1", valid(42, "499.00"));
        Reply b = initiate("k2", valid(42, "499.00"));

        assertTrue(!a.paymentRef().equals(b.paymentRef()));
        assertEquals(2, store.size());
    }

    // ---- concurrency ----

    @Test
    void concurrentRequestsWithTheSameKeyCreateExactlyOnePayment() throws Exception {
        int threads = 8;
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                String key = "race-" + round;
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Reply>> replies = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    replies.add(pool.submit(() -> {
                        go.await();
                        return initiate(key, valid(7, "49.00"));
                    }));
                }
                go.countDown();
                Set<String> refs = new HashSet<>();
                for (Future<Reply> f : replies) {
                    Reply r = f.get(30, TimeUnit.SECONDS);
                    assertEquals(202, r.status(), "round " + round + ": " + r.body());
                    refs.add(r.paymentRef());
                }
                assertEquals(1, refs.size(), "round " + round + " made " + refs);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(rounds, store.size()); // one payment per key, nothing extra
    }

    // ---- validation ----

    @Test
    void badInputIs400InvalidRequestAndCreatesNothing() throws Exception {
        List<String> bad = List.of(
                valid(42, "0"), valid(42, "0.00"), valid(42, "-5.00"), valid(42, "10.001"),
                valid(42, "abc"), valid(42, ""), valid(42, "1e2"), valid(42, "+5.00"),
                body(42, "10.5", "\"INR\""),               // a JSON number, not a string
                body(42, "null", "\"INR\""),
                "{\"orderId\":42,\"currency\":\"INR\"}",     // amount missing
                body(42, "\"49.00\"", "\"USD\""),
                body(42, "\"49.00\"", "null"),
                body(0, "\"49.00\"", "\"INR\""),
                body("null", "\"49.00\"", "\"INR\""),
                "not json");
        for (String json : bad) {
            Reply r = initiate("k-bad", json);
            assertEquals(400, r.status(), json + " -> " + r.body());
            assertTrue(r.body().contains("INVALID_REQUEST"), json + " -> " + r.body());
        }
        assertEquals(0, store.size());
    }

    @Test
    void aMissingOrBlankIdempotencyKeyIs400() throws Exception {
        Reply missing = initiate(null, valid(42, "49.00"));
        Reply blank = initiate("  ", valid(42, "49.00"));

        assertEquals(400, missing.status());
        assertTrue(missing.body().contains("INVALID_REQUEST"));
        assertEquals(400, blank.status());
        assertEquals(0, store.size());
    }

    // ---- modes ----

    @Test
    void error5xxModeReplies500AndCreatesNothing() throws Exception {
        mode("ERROR_5XX", 0);

        Reply r = initiate("k1", valid(42, "49.00"));

        assertEquals(500, r.status());
        assertEquals(0, store.size());
    }

    @Test
    void reject4xxModeReplies400AndCreatesNothing() throws Exception {
        mode("REJECT_4XX", 0);

        Reply r = initiate("k1", valid(42, "49.00"));

        assertEquals(400, r.status());
        assertTrue(r.body().contains("PAYMENT_REJECTED"), r.body());
        assertEquals(0, store.size());
    }

    @Test
    void slowAfterCreatesThePaymentAndTheRetryWithTheSameKeyGetsTheSameOne() throws Exception {
        mode("SLOW_AFTER", 1500);

        // The client gives up at 300 ms, long before the 1.5 s reply.
        assertThrows(HttpTimeoutException.class,
                () -> post("/api/payments/initiate", "k1", valid(42, "49.00"), Duration.ofMillis(300)));

        // The payment exists although the client never saw the answer.
        assertEquals(1, store.size());
        String created = store.find("k1").orElseThrow().paymentRef();

        mode("HEALTHY", 0);
        Reply retry = initiate("k1", valid(42, "49.00"));

        assertEquals(202, retry.status());
        assertEquals(created, retry.paymentRef());
        assertEquals(1, store.size());
    }

    @Test
    void slowBeforeCreatesNothingUntilTheDelayHasPassed() throws Exception {
        mode("SLOW_BEFORE", 1000);

        assertThrows(HttpTimeoutException.class,
                () -> post("/api/payments/initiate", "k1", valid(42, "49.00"), Duration.ofMillis(200)));

        assertEquals(0, store.size()); // still waiting, nothing created yet

        // The server thread is still sleeping and WILL create k1 when it wakes,
        // even though the client gave up. Wait for that, so the payment cannot
        // land in the next test's store (it made the next test flaky).
        long deadline = System.nanoTime() + Duration.ofMillis(LEFTOVER_PAYMENT_TIMEOUT_MS).toNanos();
        while (store.find("k1").isEmpty()) {
            assertTrue(System.nanoTime() < deadline,
                    "the delayed payment k1 never appeared within " + LEFTOVER_PAYMENT_TIMEOUT_MS + " ms");
            Thread.sleep(25);
        }
    }

    // The stub's SLOW_BEFORE delay is 1000 ms; about 800 ms of it is left after
    // the client gives up. 5000 ms is that plus a wide margin for a slow machine.
    private static final long LEFTOVER_PAYMENT_TIMEOUT_MS = 5000;

    @Test
    void anUnknownModeIs400AndTheModeIsUnchanged() throws Exception {
        Reply r = post("/admin/mode", null, "{\"mode\":\"NOPE\",\"delayMs\":0}", Duration.ofSeconds(10));

        assertEquals(400, r.status());
        assertEquals(Mode.HEALTHY, modes.get().mode());
    }
}
