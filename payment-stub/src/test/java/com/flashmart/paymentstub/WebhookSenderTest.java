package com.flashmart.paymentstub;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stub's webhook sender against a fake Order (a JDK {@link HttpServer} that
 * records every request and answers scripted statuses). Short timings: retry
 * every 100 ms, give up after 1 s. Says nothing about a real gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookSenderTest {

    private static final String SECRET = "test-only-webhook-secret";

    record Received(byte[] body, String signature) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final ConcurrentLinkedQueue<Integer> SCRIPT = new ConcurrentLinkedQueue<>();
    private static final HttpServer ORDER;

    static {
        try {
            ORDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ORDER.createContext("/webhooks/payment", exchange -> {
            RECEIVED.add(new Received(exchange.getRequestBody().readAllBytes(),
                    exchange.getRequestHeaders().getFirst("X-Signature")));
            Integer status = SCRIPT.poll();
            exchange.sendResponseHeaders(status == null ? 200 : status, -1);
            exchange.close();
        });
        ORDER.start();
    }

    @DynamicPropertySource
    static void webhookProperties(DynamicPropertyRegistry registry) {
        registry.add("webhook.url", () -> "http://127.0.0.1:" + ORDER.getAddress().getPort() + "/webhooks/payment");
        registry.add("webhook.retry-interval-ms", () -> 100);
        registry.add("webhook.max-duration-ms", () -> 1000);
        registry.add("webhook.request-timeout-ms", () -> 500);
        registry.add("webhook.after-reply-delay-ms", () -> 0);
    }

    @AfterAll
    static void stopOrder() {
        ORDER.stop(0);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private PaymentStore store;

    @Autowired
    private AutoWebhookState auto;

    @Autowired
    private ObjectMapper json;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void reset() {
        RECEIVED.clear();
        SCRIPT.clear();
        store.clear();
        auto.set(AutoWebhookState.Status.OFF, AutoWebhookState.Timing.AFTER_REPLY);
    }

    // ---- helpers ----

    private HttpResponse<String> post(String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Payment initiate(long orderId, String amount) throws Exception {
        HttpResponse<String> reply = post("/api/payments/initiate",
                "{\"orderId\":" + orderId + ",\"amount\":\"" + amount + "\",\"currency\":\"INR\"}", "order-" + orderId);
        assertEquals(202, reply.statusCode(), reply.body());
        return store.find("order-" + orderId).orElseThrow();
    }

    private JsonNode received(int i) {
        return json.readTree(RECEIVED.get(i).body());
    }

    /** Waits until the fake Order has seen {@code n} requests, then a little longer to catch any extra. */
    private void awaitReceived(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (RECEIVED.size() < n && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(400);
    }

    private static String hmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    // ---- tests ----

    @Test
    void signatureIsAnIndependentHmacOfTheExactBytesSentAndTheBodyUsesTheStoredPayment() throws Exception {
        Payment payment = initiate(41, "499.00");

        HttpResponse<String> reply = post("/admin/webhook", "{\"order_id\":41}", null);

        assertEquals(200, reply.statusCode(), reply.body());
        assertEquals(1, RECEIVED.size());
        assertEquals(hmac(RECEIVED.getFirst().body()), RECEIVED.getFirst().signature());
        JsonNode body = received(0);
        assertEquals(41, body.get("order_id").longValue());
        assertEquals(payment.paymentRef(), body.get("payment_ref").stringValue());
        assertEquals("SUCCESS", body.get("status").stringValue());
        assertTrue(body.get("amount").isString(), "money is a JSON string");
        assertEquals("499.00", body.get("amount").stringValue());
    }

    @Test
    void retriesOn409ThenStopsAtThe200() throws Exception {
        initiate(42, "10.00");
        SCRIPT.addAll(List.of(409, 409, 200));

        HttpResponse<String> reply = post("/admin/webhook", "{\"order_id\":42}", null);

        assertEquals(200, reply.statusCode());
        assertTrue(reply.body().contains("\"attempts\":3"), reply.body());
        awaitReceived(3);
        assertEquals(3, RECEIVED.size(), "no attempt after the 2xx");
        assertEquals(RECEIVED.get(0).signature(), RECEIVED.get(2).signature(), "the same bytes every attempt");
    }

    @Test
    void givesUpAfterTheMaxDuration() throws Exception {
        initiate(43, "10.00");
        for (int i = 0; i < 100; i++) {
            SCRIPT.add(500);
        }

        long started = System.nanoTime();
        HttpResponse<String> reply = post("/admin/webhook", "{\"order_id\":43}", null);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(reply.body().contains("\"delivered\":false"), reply.body());
        int attempts = RECEIVED.size();
        // 1 s window, 100 ms apart: about 10 attempts, never more than 11.
        assertTrue(attempts >= 5 && attempts <= 11, "attempts " + attempts);
        assertTrue(tookMs < 2500, "gave up in " + tookMs + " ms");
        Thread.sleep(400);
        assertEquals(attempts, RECEIVED.size(), "nothing after giving up");
    }

    @Test
    void timesRepeatsTheWholeDelivery() throws Exception {
        initiate(44, "10.00");

        post("/admin/webhook", "{\"order_id\":44,\"times\":3}", null);

        assertEquals(3, RECEIVED.size());
        assertEquals(received(0), received(2));
    }

    @Test
    void anyFieldCanBeOverridden() throws Exception {
        Payment payment = initiate(45, "499.00");

        post("/admin/webhook", "{\"order_id\":45,\"amount\":\"1.00\"}", null);
        post("/admin/webhook", "{\"order_id\":45,\"payment_ref\":\"pay_other\",\"status\":\"FAILED\"}", null);
        post("/admin/webhook", "{\"order_id\":999,\"payment_ref\":\"pay_raw\",\"amount\":\"5.00\"}", null);

        assertEquals("1.00", received(0).get("amount").stringValue());
        assertEquals(payment.paymentRef(), received(0).get("payment_ref").stringValue());
        assertEquals("pay_other", received(1).get("payment_ref").stringValue());
        assertEquals("FAILED", received(1).get("status").stringValue());
        assertEquals("499.00", received(1).get("amount").stringValue());
        assertEquals(999, received(2).get("order_id").longValue());
        assertEquals("pay_raw", received(2).get("payment_ref").stringValue());
    }

    @Test
    void aRawWebhookWithMissingFieldsIs400AndSendsNothing() throws Exception {
        HttpResponse<String> reply = post("/admin/webhook", "{\"order_id\":12345}", null);

        assertEquals(400, reply.statusCode());
        assertTrue(RECEIVED.isEmpty());
    }

    /** The first attempt happens before the initiate reply; retries continue after it. */
    @Test
    void beforeReplySendsTheFirstAttemptBeforeTheInitiateReplyAndRetriesAfter() throws Exception {
        auto.set(AutoWebhookState.Status.SUCCESS, AutoWebhookState.Timing.BEFORE_REPLY);
        SCRIPT.addAll(List.of(409, 200));

        initiate(46, "10.00");
        assertEquals(1, RECEIVED.size(), "attempt 1 was sent before the 202 came back");

        awaitReceived(2);
        assertEquals(2, RECEIVED.size());
        assertEquals("SUCCESS", received(1).get("status").stringValue());
    }

    @Test
    void afterReplySendsOnABackgroundThread() throws Exception {
        auto.set(AutoWebhookState.Status.FAILED, AutoWebhookState.Timing.AFTER_REPLY);

        initiate(47, "10.00");
        awaitReceived(1);

        assertEquals(1, RECEIVED.size());
        assertEquals("FAILED", received(0).get("status").stringValue());
    }

    /** A replayed initiate is the same payment: no second auto-webhook. Idempotency itself is unchanged. */
    @Test
    void aReplayedInitiateSendsNoSecondAutoWebhook() throws Exception {
        auto.set(AutoWebhookState.Status.SUCCESS, AutoWebhookState.Timing.BEFORE_REPLY);
        Payment first = initiate(48, "10.00");

        Payment again = initiate(48, "10.00");

        assertEquals(first.paymentRef(), again.paymentRef());
        awaitReceived(1);
        assertEquals(1, RECEIVED.size());
    }

    @Test
    void aBlankSecretFailsStartup() {
        assertThrows(IllegalStateException.class, () -> new WebhookSettings(" ", "http://x", 1, 1, 1, 0, 0));
        assertFalse(SECRET.isBlank());
    }
}
