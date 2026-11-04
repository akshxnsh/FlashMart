package com.flashmart.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Many buyers hit {@code reserve} for ONE item.
 *
 * <p>A warm-up runs first (200 buyers on product 2, ramped over 5 s, request
 * named {@code warmup}) so the JVM, the connection pool and the JIT have
 * served some traffic before the measured run. The measured scenario starts
 * only after the warm-up has finished. Read the measured numbers from the
 * {@code reserve} request, not from the global line, which includes the warm-up.
 *
 * <p>Each measured buyer sends one request with its own distinct order id
 * ({@code baseOrderId + index}), so no two buyers share an idempotency key and
 * every request really competes for the stock.
 *
 * <p>A response is OK only if it is HTTP 200 and the body has an
 * {@code outcome} field. OUT_OF_STOCK is a 200, so it is OK: the load tool
 * measures that the service answers; the database (see verify.sh) says whether
 * the answers were correct. The outcomes of OK responses are counted and one
 * summary line is printed at the end.
 *
 * <p>Properties (all optional, pass as -Dname=value): baseUrl, buyers,
 * baseOrderId, flashSaleId, productId, quantity, rampSeconds (0 = all buyers at
 * once; otherwise they arrive evenly over that many seconds).
 */
public class ReserveOneItem extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8082");
    private static final int BUYERS = Integer.getInteger("buyers", 1000);
    private static final long BASE_ORDER_ID = Long.getLong("baseOrderId", 1_000_000L);
    private static final long FLASH_SALE_ID = Long.getLong("flashSaleId", 1L);
    private static final long PRODUCT_ID = Long.getLong("productId", 1L);
    private static final int QUANTITY = Integer.getInteger("quantity", 1);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 10);

    // Warm-up: fixed on purpose, so it never touches the measured item (product 1).
    private static final int WARMUP_BUYERS = 200;
    private static final long WARMUP_PRODUCT_ID = 2L;
    private static final long WARMUP_BASE_ORDER_ID = 9_000_000L;
    private static final int WARMUP_RAMP_SECONDS = 5;

    private final AtomicInteger reserved = new AtomicInteger();
    private final AtomicInteger outOfStock = new AtomicInteger();
    private final AtomicInteger other = new AtomicInteger();

    private final HttpProtocolBuilder protocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private static Iterator<Map<String, Object>> orderIdsFrom(long base) {
        // One distinct order id per buyer: base, base+1, base+2, ...
        return Stream.iterate(0L, i -> i + 1)
                .map(i -> Map.<String, Object>of("orderId", base + i))
                .iterator();
    }

    private static HttpRequestActionBuilder reserveRequest(String name, long productId) {
        return http(name)
                .post("/internal/flash-sale-items/reserve")
                .body(StringBody("{\"orderId\":#{orderId},\"flashSaleId\":" + FLASH_SALE_ID
                        + ",\"productId\":" + productId + ",\"quantity\":" + QUANTITY + "}"))
                .check(status().is(200))
                .check(jsonPath("$.outcome").saveAs("outcome"));
    }

    private final ScenarioBuilder warmup = scenario("Warm-up")
            .feed(orderIdsFrom(WARMUP_BASE_ORDER_ID))
            .exec(reserveRequest("warmup", WARMUP_PRODUCT_ID));

    private final ScenarioBuilder buyer = scenario("Reserve one item")
            .feed(orderIdsFrom(BASE_ORDER_ID))
            .exec(reserveRequest("reserve", PRODUCT_ID))
            .exec(session -> {
                // "outcome" exists only when the check passed (HTTP 200 and the field present).
                if (session.contains("outcome")) {
                    switch (session.getString("outcome")) {
                        case "RESERVED" -> reserved.incrementAndGet();
                        case "OUT_OF_STOCK" -> outOfStock.incrementAndGet();
                        default -> other.incrementAndGet();
                    }
                }
                return session;
            });

    private static OpenInjectionStep mainInjection() {
        return RAMP_SECONDS == 0 ? atOnceUsers(BUYERS) : rampUsers(BUYERS).during(RAMP_SECONDS);
    }

    {
        setUp(warmup.injectOpen(rampUsers(WARMUP_BUYERS).during(WARMUP_RAMP_SECONDS))
                .andThen(buyer.injectOpen(mainInjection())))
                .protocols(protocol);
    }

    @Override
    public void after() {
        System.out.printf(
                "SUMMARY buyers=%d rampSeconds=%d outcomes: RESERVED=%d OUT_OF_STOCK=%d other=%d "
                        + "(KO responses carry no outcome and are not counted here)%n",
                BUYERS, RAMP_SECONDS, reserved.get(), outOfStock.get(), other.get());
    }
}
