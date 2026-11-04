package com.flashmart.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Many buyers hit {@code reserve} for ONE item at the same moment.
 *
 * <p>Each virtual buyer sends one request with its own distinct order id
 * ({@code baseOrderId + index}), so no two buyers share an idempotency key and
 * every request really competes for the stock.
 *
 * <p>A response counts OK only if it is HTTP 200 and the body has an
 * {@code outcome} field. OUT_OF_STOCK is a 200, so it is OK: the load tool
 * measures the service answering, and the database (see verify.sh) says whether
 * the answers were correct.
 *
 * <p>Properties (all optional): baseUrl, buyers, baseOrderId, flashSaleId,
 * productId, quantity. Pass them as -Dname=value.
 */
public class ReserveOneItem extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8082");
    private static final int BUYERS = Integer.getInteger("buyers", 1000);
    private static final long BASE_ORDER_ID = Long.getLong("baseOrderId", 1_000_000L);
    private static final long FLASH_SALE_ID = Long.getLong("flashSaleId", 1L);
    private static final long PRODUCT_ID = Long.getLong("productId", 1L);
    private static final int QUANTITY = Integer.getInteger("quantity", 1);

    // One distinct order id per buyer: the feeder hands out baseOrderId, +1, +2, ...
    private final Iterator<Map<String, Object>> orderIds =
            Stream.iterate(0L, i -> i + 1)
                    .map(i -> Map.<String, Object>of("orderId", BASE_ORDER_ID + i))
                    .iterator();

    private final HttpProtocolBuilder protocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ScenarioBuilder buyer = scenario("Reserve one item")
            .feed(orderIds)
            .exec(http("reserve")
                    .post("/internal/flash-sale-items/reserve")
                    .body(StringBody("{\"orderId\":#{orderId},\"flashSaleId\":" + FLASH_SALE_ID
                            + ",\"productId\":" + PRODUCT_ID + ",\"quantity\":" + QUANTITY + "}"))
                    .check(status().is(200))
                    .check(jsonPath("$.outcome").exists()));

    {
        setUp(buyer.injectOpen(atOnceUsers(BUYERS))).protocols(protocol);
    }
}
