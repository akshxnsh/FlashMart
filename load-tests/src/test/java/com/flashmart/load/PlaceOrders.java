package com.flashmart.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Many distinct buyers place ONE order each, for ONE item, through the Gateway
 * ({@code POST /api/orders} on :8080). The full path: Gateway (JWT check, rate
 * limiter, signed buyer id) -> Order -> Inventory reserve -> payment-stub.
 *
 * <p>One virtual user = one buyer with its own access token (fed from
 * {@code tokens/buyers.csv}, written by make-tokens.sh), its own unique
 * {@code Idempotency-Key}, quantity 1. One order per buyer means the per-buyer
 * order limiter (5/s, burst 10) is never the thing being measured.
 *
 * <p>A warm-up runs first: WARMUP buyers (default 200, from
 * {@code tokens/warmup.csv}, never the measured buyers) order a DIFFERENT item
 * of the same sale (product 2), ramped over 5 s, request named {@code warmup}.
 * The measured scenario starts only after the warm-up has finished. Read the
 * measured numbers from the {@code place-order} request, not the global line.
 *
 * <p>A response counts as answered (OK) for any expected business status:
 * 201 created, 200 replay, 409 (out of stock / conflict), 429 (rate limited),
 * 503 (Inventory or payment unavailable). Anything else (400, 401, 500, a
 * timeout) is KO. Every status is counted, per request name, and printed at the
 * end. Gatling does not judge correctness: the database does (verify.sh,
 * check-orders.sh).
 *
 * <p>Properties (all optional, -Dname=value): baseUrl (default
 * http://localhost:8080), buyers (default 5000), warmupBuyers (default 200),
 * flashSaleId (1), productId (1), warmupProductId (2), rampSeconds (10; 0 = all
 * at once), tokensDir (default tokens, relative to load-tests/).
 */
public class PlaceOrders extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final int BUYERS = Integer.getInteger("buyers", 5000);
    private static final int WARMUP_BUYERS = Integer.getInteger("warmupBuyers", 200);
    private static final long FLASH_SALE_ID = Long.getLong("flashSaleId", 1L);
    private static final long PRODUCT_ID = Long.getLong("productId", 1L);
    private static final long WARMUP_PRODUCT_ID = Long.getLong("warmupProductId", 2L);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 10);
    private static final int WARMUP_RAMP_SECONDS = 5;
    private static final Path TOKENS = Path.of(System.getProperty("tokensDir", "tokens")).toAbsolutePath();

    /** Unique per JVM run, so Idempotency-Keys never repeat across runs either. */
    private static final long RUN_ID = System.currentTimeMillis();
    private static final AtomicLong KEY_SEQ = new AtomicLong();

    /** request name -> (HTTP status, or "none" if no response) -> count. */
    private final Map<String, Map<String, AtomicLong>> statusCounts = new ConcurrentHashMap<>();

    private final HttpProtocolBuilder protocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private static HttpRequestActionBuilder placeOrder(String name, long productId) {
        return http(name)
                .post("/api/orders")
                .header("Authorization", "Bearer #{token}")
                .header("Idempotency-Key", "#{idempotencyKey}")
                .body(StringBody("{\"flashSaleId\":" + FLASH_SALE_ID
                        + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}"))
                // Saved first, so it is recorded even when the next check fails.
                .check(status().saveAs("httpStatus"))
                .check(status().in(200, 201, 409, 429, 503));
    }

    private ScenarioBuilder buyers(String scenarioName, String requestName, String csvFile, long productId) {
        return scenario(scenarioName)
                // queue(): each token is used once; running out is an error, never a reuse.
                .feed(csv(TOKENS.resolve(csvFile).toString()).queue())
                .exec(session -> session.set("idempotencyKey",
                        "lt-" + RUN_ID + "-" + KEY_SEQ.incrementAndGet()))
                .exec(placeOrder(requestName, productId))
                .exec(session -> {
                    String code = session.contains("httpStatus") ? String.valueOf(session.getInt("httpStatus")) : "none";
                    statusCounts.computeIfAbsent(requestName, k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
                    return session.remove("httpStatus");
                });
    }

    private static OpenInjectionStep mainInjection() {
        return RAMP_SECONDS == 0 ? atOnceUsers(BUYERS) : rampUsers(BUYERS).during(RAMP_SECONDS);
    }

    {
        setUp(buyers("Warm-up", "warmup", "warmup.csv", WARMUP_PRODUCT_ID)
                        .injectOpen(rampUsers(WARMUP_BUYERS).during(WARMUP_RAMP_SECONDS))
                        .andThen(buyers("Place one order", "place-order", "buyers.csv", PRODUCT_ID)
                                .injectOpen(mainInjection())))
                .protocols(protocol);
    }

    /** Seconds until the earliest token expires, from make-tokens.sh; fails the run if they already have. */
    private static long tokenBudgetSeconds() {
        try {
            long expiresAt = Long.parseLong(Files.readString(TOKENS.resolve("expires-at.txt")).trim());
            return expiresAt - System.currentTimeMillis() / 1000;
        } catch (IOException e) {
            throw new IllegalStateException("no " + TOKENS.resolve("expires-at.txt") + ": run make-tokens.sh first", e);
        }
    }

    @Override
    public void before() {
        long left = tokenBudgetSeconds();
        System.out.printf("TOKENS budget left at start: %d s%n", left);
        if (left <= 0) {
            throw new IllegalStateException("access tokens have expired: run make-tokens.sh again");
        }
    }

    @Override
    public void after() {
        System.out.printf("TOKENS budget left at end: %d s (negative = some requests may have used expired tokens)%n",
                tokenBudgetSeconds());
        for (String name : new String[] {"warmup", "place-order"}) {
            Map<String, Long> sorted = new TreeMap<>();
            statusCounts.getOrDefault(name, Map.of()).forEach((k, v) -> sorted.put(k, v.get()));
            System.out.printf("STATUS %s buyers=%d rampSeconds=%d: %s%n", name,
                    name.equals("warmup") ? WARMUP_BUYERS : BUYERS,
                    name.equals("warmup") ? WARMUP_RAMP_SECONDS : RAMP_SECONDS, sorted);
        }
    }
}
