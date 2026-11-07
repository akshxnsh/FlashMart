package com.flashmart.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code lb://} routes when discovery has no usable instance.
 *
 * <ul>
 *   <li>{@code inventory-service} has NO instance: {@code 503 SERVICE_UNAVAILABLE}.</li>
 *   <li>{@code order-service}'s only instance reads the whole request and then
 *       resets the connection (RST) without answering. The Gateway must try it
 *       EXACTLY once: a retried {@code POST /api/orders} is a second order
 *       attempt the buyer never sent (decisions.md, "Day 8", addendum
 *       "Gateway and discovery", point 6).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayContractTest.FixedClock.class)
class DiscoveryFailureTest {

    static final ResettingServer RESETTING_ORDER = new ResettingServer();

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry r) {
        // inventory-service deliberately absent; order-service is the resetting server.
        r.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri",
                () -> "http://127.0.0.1:" + RESETTING_ORDER.port());
    }

    @BeforeEach
    void reset() {
        RESETTING_ORDER.connections.set(0);
        RESETTING_ORDER.requests.set(0);
    }

    @AfterAll
    static void stop() throws IOException {
        RESETTING_ORDER.close();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + TestTokens.user(42))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "k1")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noInstanceIs503ServiceUnavailable() throws Exception {
        HttpResponse<String> r = send("GET", "/api/flash-sales?page=0", null);

        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        assertThat(r.body()).contains("\"code\":\"SERVICE_UNAVAILABLE\"").contains("\"message\":");
    }

    @Test
    void aPostWhoseConnectionIsResetAfterSendingIsAttemptedExactlyOnce() throws Exception {
        HttpResponse<String> r = send("POST", "/api/orders", "{\"flashSaleId\":7,\"items\":[]}");

        assertThat(r.statusCode()).as(r.body()).isGreaterThanOrEqualTo(500);
        // Give any delayed retry time to show up before counting.
        Thread.sleep(1000);
        assertThat(RESETTING_ORDER.requests.get()).as("full requests received").isEqualTo(1);
        assertThat(RESETTING_ORDER.connections.get()).as("connections opened").isEqualTo(1);
    }

    /** Accepts, reads one full HTTP request (headers + Content-Length body), then resets the connection. */
    static final class ResettingServer implements AutoCloseable {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        private final ServerSocket server;

        ResettingServer() {
            try {
                server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Thread t = new Thread(this::acceptLoop, "resetting-order-stub");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    connections.incrementAndGet();
                    try (s) {
                        if (readOneRequest(s.getInputStream())) {
                            requests.incrementAndGet();
                        }
                        s.setSoLinger(true, 0); // close() now sends RST, not FIN
                    }
                } catch (IOException e) {
                    // closed, or the peer went away: keep accepting
                }
            }
        }

        private static boolean readOneRequest(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            int c;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched < 4 && (c = in.read()) != -1) {
                head.write(c);
                matched = c == end[matched] ? matched + 1 : (c == '\r' ? 1 : 0);
            }
            if (matched < 4) {
                return false;
            }
            int length = 0;
            for (String line : head.toString(StandardCharsets.US_ASCII).split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).trim());
                }
            }
            return in.readNBytes(length).length == length;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
