package com.flashmart.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Gateway's contract over real HTTP on loopback: a real client calls the
 * running Gateway, which forwards to {@link StubDownstream} servers that record
 * exactly what arrived. Subclasses decide how the Gateway finds the stubs
 * (fixed addresses, or {@code lb://} through discovery), so the same assertions
 * run against both.
 *
 * <p>The Gateway's clock is fixed at {@link TestTokens#NOW}, so token expiry
 * and {@code X-Auth-Timestamp} are exact.
 */
abstract class GatewayContractTest {

    static final StubDownstream IDENTITY = new StubDownstream("identity");
    static final StubDownstream ORDER = new StubDownstream("order");
    static final StubDownstream INVENTORY = new StubDownstream("inventory");

    static final String WEBHOOK_SECRET = "the-payment-gateway-webhook-secret";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TestTokens.NOW, ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void clearStubs() {
        IDENTITY.clear();
        ORDER.clear();
        INVENTORY.clear();
    }

    // ---- helpers ----

    HttpResponse<String> send(String method, String path, String bearer, String body, String... headers)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postOrder(String bearer) throws Exception {
        return send("POST", "/api/orders", bearer, "{\"flashSaleId\":7}", "Idempotency-Key", "k1");
    }

    static void assertError(HttpResponse<String> r, int status, String code) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/json"));
        assertThat(r.body()).contains("\"code\":\"" + code + "\"").contains("\"message\":");
    }

    static void assertNothingForwarded() {
        assertThat(IDENTITY.received()).isEmpty();
        assertThat(ORDER.received()).isEmpty();
        assertThat(INVENTORY.received()).isEmpty();
    }

    static void assertNoIdentityHeaders(StubDownstream.Received r) {
        assertThat(r.header("X-Buyer-Id")).isNull();
        assertThat(r.header("X-Auth-Timestamp")).isNull();
        assertThat(r.header("X-Auth-Signature")).isNull();
    }

    static void assertSignedFor(StubDownstream.Received r, String buyerId) {
        assertThat(r.header("X-Buyer-Id")).isEqualTo(buyerId);
        assertThat(r.header("X-Auth-Timestamp")).isEqualTo(Long.toString(TestTokens.NOW.getEpochSecond()));
        assertThat(r.header("X-Auth-Signature")).isEqualTo(TestTokens.hmacHex(TestTokens.SIGNING_SECRET,
                buyerId + "." + TestTokens.NOW.getEpochSecond()));
        // Exactly one value each: a client copy must not survive beside the Gateway's.
        assertThat(r.headers().get("X-Buyer-Id")).hasSize(1);
        assertThat(r.headers().get("X-Auth-Timestamp")).hasSize(1);
        assertThat(r.headers().get("X-Auth-Signature")).hasSize(1);
    }

    // ---- 401 / 403 ----

    @Test
    void noTokenIs401AndNothingIsForwarded() throws Exception {
        assertError(postOrder(null), 401, "UNAUTHORIZED");
        assertError(send("GET", "/api/flash-sales", null, null), 401, "UNAUTHORIZED");
        assertNothingForwarded();
    }

    @Test
    void aNonBearerAuthorizationIs401() throws Exception {
        HttpResponse<String> r = send("POST", "/api/orders", null, "{}",
                "Authorization", "Basic dXNlcjpwYXNz", "Idempotency-Key", "k1");
        assertError(r, 401, "UNAUTHORIZED");
        assertNothingForwarded();
    }

    @Test
    void anExpiredTokenIs401() throws Exception {
        String expired = TestTokens.token("7", "user", TestTokens.NOW.minusSeconds(1));
        assertError(postOrder(expired), 401, "UNAUTHORIZED");
        assertNothingForwarded();
    }

    @Test
    void aTamperedSignatureIs401() throws Exception {
        String good = TestTokens.user(7);
        int dot = good.lastIndexOf('.');
        char last = good.charAt(good.length() - 1);
        String tamperedSig = good.substring(0, good.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertError(postOrder(tamperedSig), 401, "UNAUTHORIZED");

        // Payload swapped for one saying sub=8, original signature kept.
        String other = TestTokens.user(8);
        String swapped = other.substring(0, other.lastIndexOf('.')) + good.substring(dot);
        assertError(postOrder(swapped), 401, "UNAUTHORIZED");
        assertNothingForwarded();
    }

    @Test
    void algNoneAndAnyAlgorithmOtherThanHs256Are401() throws Exception {
        assertError(postOrder(TestTokens.algNone(7)), 401, "UNAUTHORIZED");
        assertError(postOrder(TestTokens.hs512User(7)), 401, "UNAUTHORIZED");
        assertNothingForwarded();
    }

    @Test
    void aTokenWithoutSubOrTypIs401() throws Exception {
        assertError(postOrder(TestTokens.token(null, "user", TestTokens.NOW.plusSeconds(900))), 401, "UNAUTHORIZED");
        assertError(postOrder(TestTokens.token("7", null, TestTokens.NOW.plusSeconds(900))), 401, "UNAUTHORIZED");
        assertNothingForwarded();
    }

    @Test
    void aValidBrandTokenOnOrdersIs403() throws Exception {
        String brand = TestTokens.token("7", "brand", TestTokens.NOW.plusSeconds(900));
        assertError(postOrder(brand), 403, "FORBIDDEN");
        // Percent-encoding the path does not slip past the type check.
        assertError(send("POST", "/api/%6Frders", brand, "{}"), 403, "FORBIDDEN");
        assertNothingForwarded();
    }

    @Test
    void aBrandTokenIsNotRefusedOutsideOrders() throws Exception {
        String brand = TestTokens.token("7", "brand", TestTokens.NOW.plusSeconds(900));
        HttpResponse<String> r = send("GET", "/api/flash-sales?page=0", brand, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertSignedFor(INVENTORY.only(), "7");
    }

    // ---- routing and signed headers ----

    @Test
    void anAuthenticatedOrderArrivesWithCorrectlySignedHeaders() throws Exception {
        HttpResponse<String> r = postOrder(TestTokens.user(42));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"stub\":\"order\"}");
        StubDownstream.Received got = ORDER.only();
        assertThat(got.method()).isEqualTo("POST");
        assertThat(got.rawPath()).isEqualTo("/api/orders");
        assertThat(got.header("Idempotency-Key")).isEqualTo("k1");
        assertSignedFor(got, "42");
    }

    @Test
    void eachPrefixGoesToItsService() throws Exception {
        String t = TestTokens.user(5);
        assertThat(send("GET", "/api/products/3", t, null).body()).isEqualTo("{\"stub\":\"inventory\"}");
        assertThat(send("GET", "/api/flash-sales", t, null).body()).isEqualTo("{\"stub\":\"inventory\"}");
        assertThat(send("GET", "/api/orders/9", t, null).body()).isEqualTo("{\"stub\":\"order\"}");
        assertThat(send("GET", "/api/auth/me", t, null).body()).isEqualTo("{\"stub\":\"identity\"}");
    }

    @Test
    void clientSentIdentityHeadersNeverReachDownstreamOnAnAuthenticatedRoute() throws Exception {
        HttpResponse<String> r = send("POST", "/api/orders", TestTokens.user(42), "{}",
                "Idempotency-Key", "k1",
                "X-Buyer-Id", "1",
                "x-auth-timestamp", "999",
                "X-AUTH-SIGNATURE", "deadbeef");
        assertThat(r.statusCode()).isEqualTo(200);
        assertSignedFor(ORDER.only(), "42");
    }

    @Test
    void clientSentIdentityHeadersAreStrippedOnExemptRoutes() throws Exception {
        for (String path : new String[] {"/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout"}) {
            IDENTITY.clear();
            HttpResponse<String> r = send("POST", path, null, "{}",
                    "X-Buyer-Id", "1", "X-Auth-Timestamp", "999", "X-Auth-Signature", "deadbeef");
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            StubDownstream.Received got = IDENTITY.only();
            assertThat(got.rawPath()).isEqualTo(path);
            assertNoIdentityHeaders(got);
        }
    }

    @Test
    void exemptRoutesPassWithNoTokenAndOnlyByPost() throws Exception {
        assertThat(send("POST", "/api/auth/login", null, "{}").statusCode()).isEqualTo(200);
        assertThat(IDENTITY.received()).hasSize(1);
        // Not POST: not exempt, so a token is required.
        assertError(send("GET", "/api/auth/login", null, null), 401, "UNAUTHORIZED");
        assertThat(IDENTITY.received()).hasSize(1);
    }

    @Test
    void theWebhookBodyArrivesByteForByteWithItsSignatureAndNoIdentityHeaders() throws Exception {
        // Odd spacing, key order and a non-ASCII character: any re-serialisation would change the bytes.
        String body = "{ \"order_id\":17,  \"payment_ref\":\"pay_order-17\",\"status\":\"SUCCESS\",\"amount\":\"149.00\",\"note\":\"Café\" }\n";
        String signature = TestTokens.hmacHex(WEBHOOK_SECRET, body);
        HttpResponse<String> r = send("POST", "/webhooks/payment", null, body,
                "X-Signature", signature,
                "X-Buyer-Id", "1", "X-Auth-Timestamp", "999", "X-Auth-Signature", "deadbeef");
        assertThat(r.statusCode()).isEqualTo(200);
        StubDownstream.Received got = ORDER.only();
        assertThat(got.rawPath()).isEqualTo("/webhooks/payment");
        assertThat(got.body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(got.header("X-Signature")).isEqualTo(signature);
        // The receiver's own check, recomputed over what actually arrived.
        assertThat(TestTokens.hmacHex(WEBHOOK_SECRET, new String(got.body(), StandardCharsets.UTF_8)))
                .isEqualTo(got.header("X-Signature"));
        assertNoIdentityHeaders(got);
    }

    // ---- never routed ----

    @Test
    void internalPathsAre404EvenWithAValidToken() throws Exception {
        String t = TestTokens.user(5);
        assertError(send("POST", "/internal/flash-sale-items/reserve", t, "{}"), 404, "NOT_FOUND");
        assertError(send("GET", "/internal/orders/1", null, null), 404, "NOT_FOUND");
        assertNothingForwarded();
    }

    /**
     * A raw {@code ..} path (curl --path-as-is, or any hand-written client)
     * matches the Inventory route by prefix; if forwarded, Inventory's Tomcat
     * would normalise it to {@code /internal/...}, the unauthenticated reserve
     * endpoint. It must never reach a service.
     */
    @Test
    void dotSegmentsCannotReachInternal() throws Exception {
        String t = TestTokens.user(5);
        for (String path : new String[] {
                "/api/products/../../internal/flash-sale-items/reserve",
                "/api/products/%2e%2e/%2e%2e/internal/flash-sale-items/reserve",
                "/api/products/..%2f..%2finternal/flash-sale-items/reserve",
                "/api/products/..;/..;/internal/flash-sale-items/reserve",
                "/api/products/.%2E/%2e./internal/flash-sale-items/reserve",
                "/api/products/..%5c..%5cinternal/flash-sale-items/reserve"}) {
            int status = rawPost(path, t);
            assertThat(status).as(path).isEqualTo(400);
        }
        for (StubDownstream.Received got : INVENTORY.received()) {
            assertThat(got.rawPath()).doesNotContain("..").doesNotContainIgnoringCase("%2e");
        }
        assertThat(INVENTORY.received()).isEmpty();
    }

    /** Sends the request line exactly as given (java.net.http could normalise it). Returns the status code. */
    int rawPost(String rawPath, String bearer) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(10_000);
            String req = "POST " + rawPath + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer " + bearer + "\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 2\r\nConnection: close\r\n\r\n{}";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                line.write(c);
            }
            String statusLine = line.toString(StandardCharsets.US_ASCII);
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
