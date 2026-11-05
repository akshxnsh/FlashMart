package com.flashmart.paymentstub;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Delivers signed webhooks to Order the way the stub assumes a real gateway
 * does: retry every {@code retry-interval-ms} on any non-2xx or I/O error,
 * stop at the first 2xx, give up after {@code max-duration-ms} from the first
 * attempt. Every attempt is logged.
 *
 * <p>The body is serialised ONCE to bytes, and those exact bytes are both
 * signed (HMAC-SHA256, hex in {@code X-Signature}) and sent, so the signature
 * always covers what Order receives.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    public static final String SIGNATURE_HEADER = "X-Signature";

    /** How one delivery ended. {@code lastStatus} is -1 when the last attempt was an I/O error. */
    public record Delivery(int attempts, int lastStatus, boolean delivered) {
    }

    private final WebhookSettings settings;
    private final ObjectMapper json;
    private final HttpClient http;
    private final ScheduledExecutorService async;

    public WebhookSender(WebhookSettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(settings.requestTimeoutMs())).build();
        AtomicInteger n = new AtomicInteger();
        this.async = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "webhook-sender-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() {
        async.shutdownNow();
    }

    /** One full delivery on the calling thread: attempts until 2xx or the give-up window ends. */
    public Delivery deliver(WebhookMessage message) {
        return retryFrom(message, System.nanoTime(), 1);
    }

    /** A full delivery on a background thread, starting after {@code delayMs}. */
    public void deliverLater(WebhookMessage message, long delayMs) {
        async.schedule(() -> deliver(message), delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * The first attempt on the calling thread (so it happens before the caller
     * returns), any retries on a background thread. The give-up window counts
     * from this first attempt.
     */
    public Delivery firstAttemptNowRestLater(WebhookMessage message) {
        long start = System.nanoTime();
        int status = attempt(message, 1, start);
        if (is2xx(status)) {
            return new Delivery(1, status, true);
        }
        async.schedule(() -> retryFrom(message, start, 2), settings.retryIntervalMs(), TimeUnit.MILLISECONDS);
        return new Delivery(1, status, false);
    }

    private Delivery retryFrom(WebhookMessage message, long start, int firstAttempt) {
        int attemptNo = firstAttempt;
        while (true) {
            int status = attempt(message, attemptNo, start);
            if (is2xx(status)) {
                return new Delivery(attemptNo, status, true);
            }
            long nextAtMs = elapsedMs(start) + settings.retryIntervalMs();
            if (nextAtMs > settings.maxDurationMs()) {
                log.warn("webhook order={} ref={} {}: GAVE UP after {} attempts in {} ms (last status {})",
                        message.orderId(), message.paymentRef(), message.status(), attemptNo, elapsedMs(start),
                        status);
                return new Delivery(attemptNo, status, false);
            }
            try {
                Thread.sleep(settings.retryIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Delivery(attemptNo, status, false);
            }
            attemptNo++;
        }
    }

    /** One POST. Returns the HTTP status, or -1 on an I/O error or timeout. */
    private int attempt(WebhookMessage message, int attemptNo, long start) {
        byte[] body = body(message);
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url()))
                .timeout(Duration.ofMillis(settings.requestTimeoutMs()))
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, HexFormat.of().formatHex(sign(body)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        int status;
        String detail;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            detail = response.body();
        } catch (IOException e) {
            status = -1;
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = -1;
            detail = "interrupted";
        }
        log.info("webhook order={} ref={} {} amount={} attempt {} at +{} ms -> {} {}", message.orderId(),
                message.paymentRef(), message.status(), message.amount(), attemptNo, elapsedMs(start),
                status == -1 ? "IO_ERROR" : status, detail);
        return status;
    }

    /** {@code {order_id, payment_ref, status, amount}}; amount stays a string. */
    byte[] body(WebhookMessage message) {
        ObjectNode node = json.createObjectNode();
        node.put("order_id", message.orderId());
        node.put("payment_ref", message.paymentRef());
        node.put("status", message.status());
        node.put("amount", message.amount());
        return json.writeValueAsBytes(node);
    }

    private byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean is2xx(int status) {
        return status >= 200 && status < 300;
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
