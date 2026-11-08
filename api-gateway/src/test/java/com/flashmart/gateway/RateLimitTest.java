package com.flashmart.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiters against a REAL Redis (the compose one on localhost:6379;
 * a missing Redis fails these tests, it does not skip them). Real HTTP into
 * the Gateway, stub downstreams that count what got through.
 *
 * <p>Low limits here: orders replenish 1/s, burst 3; auth replenish 1/s,
 * burst 2. The calls in one test take milliseconds, so the bucket refills by
 * far less than one token between them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway-ratelimit.orders.replenish-rate=1", "gateway-ratelimit.orders.burst=3",
        "gateway-ratelimit.auth.replenish-rate=1", "gateway-ratelimit.auth.burst=2"})
@ActiveProfiles("fixed")
@Import(GatewayContractTest.FixedClock.class)
class RateLimitTest {

    static final StubDownstream IDENTITY = new StubDownstream("identity");
    static final StubDownstream ORDER = new StubDownstream("order");
    static final StubDownstream INVENTORY = new StubDownstream("inventory");

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        r.add("flashmart.routes.identity-uri", IDENTITY::uri);
        r.add("flashmart.routes.order-uri", ORDER::uri);
        r.add("flashmart.routes.inventory-uri", INVENTORY::uri);
    }

    @AfterAll
    static void stop() {
        IDENTITY.close();
        ORDER.close();
        INVENTORY.close();
    }

    @LocalServerPort
    int port;

    @Autowired
    ReactiveStringRedisTemplate redis;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void cleanSlate() {
        redis.getConnectionFactory().getReactiveConnection().serverCommands().flushDb().block();
        // Warm-up: the first limiter call connects to Redis and loads its script,
        // which can take over a second; at 1 token/s a token would refill
        // mid-test. A throwaway buyer pays that cost before the real calls.
        try {
            order(9999);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        IDENTITY.clear();
        ORDER.clear();
        INVENTORY.clear();
    }

    private HttpResponse<String> call(String method, String path, String token, InetAddress from, String... headers)
            throws Exception {
        HttpClient client = from == null ? http
                : HttpClient.newBuilder().localAddress(from).connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> order(long buyer, String... headers) throws Exception {
        return call("POST", "/api/orders", TestTokens.user(buyer), null, headers);
    }

    /**
     * Sends until the first 429 and returns how many requests passed before it.
     * Spring Cloud Gateway hands the Lua script the time in WHOLE seconds, so a
     * test that happens to cross a wall-clock second gets one extra token: the
     * honest assertion is "burst, or burst + 1", never an exact count.
     */
    private int passedBeforeLimit(int burst, java.util.concurrent.Callable<HttpResponse<String>> request)
            throws Exception {
        for (int passed = 0; passed <= burst + 1; passed++) {
            HttpResponse<String> r = request.call();
            if (r.statusCode() == 429) {
                assertRateLimited(r);
                assertThat(passed).as("requests that passed before the 429").isBetween(burst, burst + 1);
                return passed;
            }
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        }
        throw new AssertionError("no 429 within " + (burst + 2) + " requests");
    }

    private static void assertRateLimited(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(429);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
        assertThat(r.body()).contains("\"code\":\"RATE_LIMITED\"").contains("\"message\":");
        assertThat(r.headers().firstValue("Retry-After")).hasValue("1");
    }

    @Test
    void ordersAllowTheBurstThenAnswer429WithoutReachingOrder() throws Exception {
        int passed = passedBeforeLimit(3, () -> order(9001));
        assertRateLimited(order(9001));
        assertThat(ORDER.received()).as("only the requests that passed reached Order").hasSize(passed);
    }

    @Test
    void aLimitedBuyerDoesNotLimitAnotherBuyer() throws Exception {
        passedBeforeLimit(3, () -> order(9010));
        assertThat(order(9011).statusCode()).isEqualTo(200);
    }

    /**
     * The ordering proof. The limiter's key is X-Buyer-Id, which only the JWT
     * filter may set. If the limiter ran first it would read a header the
     * client chose, and these would fail:
     * (1) requests with a bad token are 401 (auth ran first), never 429, and
     *     never consume the forged id's bucket;
     * (2) a valid buyer's bucket is charged to the token's buyer, not to a
     *     different X-Buyer-Id the client sent.
     */
    @Test
    void theJwtFilterRunsBeforeTheLimiter() throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> r = call("POST", "/api/orders", "not-a-token", null, "X-Buyer-Id", "9020");
            assertThat(r.statusCode()).as("bad token " + (i + 1)).isEqualTo(401);
        }
        // Buyer 9020 still has its full burst: the forged requests were never counted.
        passedBeforeLimit(3, () -> order(9020));

        // Buyer 9021 sending 9022's id is charged as 9021: the 4th is limited, and 9022 is untouched.
        passedBeforeLimit(3, () -> order(9021, "X-Buyer-Id", "9022"));
        assertThat(order(9022).statusCode()).isEqualTo(200);
        assertThat(ORDER.received()).allSatisfy(got ->
                assertThat(got.header("X-Buyer-Id")).isIn("9020", "9021", "9022"));
    }

    @Test
    void authIsLimitedPerClientIpAndForwardedForIsNotTrusted() throws Exception {
        InetAddress other = InetAddress.getByName("127.0.0.2");
        // Each request claims a different X-Forwarded-For; the bucket is still the socket's.
        int[] n = {0};
        int passed = passedBeforeLimit(2, () -> call("POST", "/api/auth/login", null, null,
                "X-Forwarded-For", "10.0.0." + (++n[0])));
        assertRateLimited(call("POST", "/api/auth/signup", null, null));
        // A different client address has its own bucket.
        assertThat(call("POST", "/api/auth/login", null, other).statusCode()).isEqualTo(200);
        assertThat(IDENTITY.received()).hasSize(passed + 1);
    }

    @Test
    void getsOnOrdersTheWebhookAndBrowseAreNotLimited() throws Exception {
        String t = TestTokens.user(9030);
        for (int i = 0; i < 8; i++) {
            assertThat(call("GET", "/api/orders/1", t, null).statusCode()).isEqualTo(200);
            assertThat(call("GET", "/api/flash-sales?page=0", t, null).statusCode()).isEqualTo(200);
            assertThat(call("POST", "/webhooks/payment", null, null, "X-Signature", "ab").statusCode()).isEqualTo(200);
        }
    }
}
