package com.flashmart.order.service;

import com.flashmart.order.TestRows;
import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The payment step of the create path (decisions.md, Day 5) end to end inside
 * Order: real Postgres, the real Feign client with the real retry and breaker,
 * and a JDK {@link HttpServer} STUB playing the gateway. Inventory is mocked at
 * {@link InventoryCalls}. NOT the real payment-stub, NOT a real gateway, not
 * load-tested.
 *
 * <p>The stub answers 202 with {@code paymentRef = "pay_" + Idempotency-Key},
 * so the same key always gets the same payment, as an idempotent gateway
 * would. Shortened for speed: read timeout 300 ms, wait 50 ms.
 *
 * <p>NOT {@code @Transactional}: every step commits on its own. Buyers
 * 9000..9999 belong to this class.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OrderPaymentStepTest {

    private static final long FROM = 9000, TO = 9999;
    private static final long SALE = 7L;
    private static final int READ_TIMEOUT_MS = 300;
    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();
    private static final List<String> KEYS = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = start();

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange, int call) throws IOException;
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                KEYS.add(String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")));
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
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Autowired
    private OrderCreationService service;

    @Autowired
    private OrderCreationTransaction transaction;

    @Autowired
    private CircuitBreaker breaker;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryCalls inventory;

    private long nextBuyer = FROM;

    @BeforeEach
    void reset() {
        KEYS.clear();
        breaker.reset();
        HANDLER.set((exchange, call) -> accepted(exchange));
        given(inventory.reserve(any())).willReturn(
                new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product"));
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void accepted(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        long orderId = Long.parseLong(key.substring("order-".length()));
        respond(exchange, 202, "{\"paymentRef\":\"pay_" + key + "\",\"orderId\":" + orderId
                + ",\"amount\":\"59.00\",\"status\":\"PENDING\"}");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final CreateOrderRequest ONE_ITEM =
            new CreateOrderRequest(SALE, List.of(new CreateOrderRequest.Item(11L, 1)));

    private Map<String, Object> order(long id) {
        return jdbc.queryForMap("select status, amount, payment_ref, failure_detail, expires_at, "
                + "extract(epoch from expires_at - clock_timestamp()) as seconds_left from orders where id = ?", id);
    }

    private long orderIdOf(long buyer) {
        return jdbc.queryForObject("select id from orders where buyer_id = ?", Long.class, buyer);
    }

    private long orderRows(long buyer) {
        return jdbc.queryForObject("select count(*) from orders where buyer_id = ?", Long.class, buyer);
    }

    private List<String> outboxPayloads(long id) {
        return jdbc.queryForList("select payload_json::text from outbox where aggregate_id = ?", String.class, id);
    }

    private OrderRejectedException rejected(long buyer, String key) {
        return assertThrows(OrderRejectedException.class, () -> service.create(buyer, key, ONE_ITEM));
    }

    // ---- the happy path ----

    @Test
    void aStartedPaymentIs201PendingPaymentWithTheRefAndThePaymentWindow() {
        long buyer = nextBuyer++;

        CreateOrderResult result = service.create(buyer, "k1", ONE_ITEM);

        long id = result.body().orderId();
        assertTrue(result.created());
        assertEquals("PENDING_PAYMENT", result.body().status());
        assertEquals(new BigDecimal("59.00"), result.body().amount()); // 10.00 + 49.00 fee
        assertEquals("pay_order-" + id, result.body().paymentRef());
        assertEquals(List.of("order-" + id), KEYS);

        Map<String, Object> row = order(id);
        assertEquals("PENDING_PAYMENT", row.get("status"));
        assertEquals("pay_order-" + id, row.get("payment_ref"));
        double left = ((Number) row.get("seconds_left")).doubleValue();
        assertTrue(left > 355 && left <= 360, "expires in " + left + " s; want the 360 s payment window");
        assertEquals(((java.sql.Timestamp) row.get("expires_at")).toInstant(), result.body().expiresAt());
        assertTrue(outboxPayloads(id).isEmpty()); // nothing downstream reacts to a payment starting
    }

    @Test
    void aTimeoutThenASuccessGivesOneOrderAndOneRefWithTheSameKeyTwice() {
        long buyer = nextBuyer++;
        HANDLER.set((exchange, call) -> {
            if (call == 1) {
                sleep(READ_TIMEOUT_MS * 4L); // the client gives up; a real gateway may still create it
            }
            accepted(exchange);
        });

        CreateOrderResult result = service.create(buyer, "k1", ONE_ITEM);

        long id = result.body().orderId();
        assertEquals(1, orderRows(buyer));
        assertEquals(List.of("order-" + id, "order-" + id), KEYS);
        assertEquals("PENDING_PAYMENT", order(id).get("status"));
        assertEquals("pay_order-" + id, order(id).get("payment_ref"));
    }

    // ---- failures ----

    @Test
    void retriesExhaustedIs503AndCancelsTheOrderWithOneOrderCancelled() {
        long buyer = nextBuyer++;
        HANDLER.set((exchange, call) -> respond(exchange, 500, "{\"code\":\"STUB_ERROR\"}"));

        OrderRejectedException e = rejected(buyer, "k1");

        long id = orderIdOf(buyer);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(ErrorCode.PAYMENT_UNAVAILABLE, e.getBody().code());
        assertEquals(2, KEYS.size()); // one retry, then give up
        Map<String, Object> row = order(id);
        assertEquals("PAYMENT_UNAVAILABLE", row.get("status"));
        assertTrue(((String) row.get("failure_detail")).contains("after 2 attempts"), (String) row.get("failure_detail"));
        assertNull(row.get("payment_ref"));
        assertEquals(List.of("{\"reason\": \"PAYMENT_UNAVAILABLE\", \"buyer_id\": " + buyer + ", \"order_id\": " + id
                + "}"), outboxPayloads(id));
    }

    @Test
    void aGateway4xxIs500PaymentRejectedNotRetriedLoggedAndStillCancels(CapturedOutput output) {
        long buyer = nextBuyer++;
        HANDLER.set((exchange, call) -> respond(exchange, 400, "{\"code\":\"INVALID_REQUEST\"}"));

        OrderRejectedException e = rejected(buyer, "k1");

        long id = orderIdOf(buyer);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatus());
        assertEquals(ErrorCode.PAYMENT_REJECTED, e.getBody().code());
        assertEquals(1, KEYS.size()); // a 4xx is never retried
        Map<String, Object> row = order(id);
        assertEquals("PAYMENT_UNAVAILABLE", row.get("status")); // so OrderCancelled releases the stock
        assertTrue(((String) row.get("failure_detail")).contains("400"), (String) row.get("failure_detail"));
        assertEquals(1, outboxPayloads(id).size());
        assertTrue(output.getOut().contains("ERROR") && output.getOut().contains("order " + id),
                "expected an ERROR log naming the order");
    }

    @Test
    void anOpenBreakerFailsAtOnceWithoutCallingTheGatewayAndStillCancels() {
        long buyer = nextBuyer++;
        breaker.transitionToOpenState();

        OrderRejectedException e = rejected(buyer, "k1");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(ErrorCode.PAYMENT_UNAVAILABLE, e.getBody().code());
        assertTrue(KEYS.isEmpty()); // no call at all
        long id = orderIdOf(buyer);
        assertEquals("PAYMENT_UNAVAILABLE", order(id).get("status"));
        assertTrue(((String) order(id).get("failure_detail")).contains("open"));
        assertEquals(1, outboxPayloads(id).size());
    }

    /**
     * The sweep expires the order while the gateway call is in flight (here the
     * stub does it, by native SQL, before answering 202). The PENDING_PAYMENT CAS
     * loses: the order stays ORDER_EXPIRED with no ref, this request writes no
     * event (the sweep owns that), and the buyer gets 409 ORDER_EXPIRED. The
     * payment that exists is refunded by the Day 6 webhook handler (not built).
     */
    @Test
    void theSweepExpiringTheOrderDuringInitiateMakesTheCasLose() {
        long buyer = nextBuyer++;
        HANDLER.set((exchange, call) -> {
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            jdbc.update("update orders set status = 'ORDER_EXPIRED' where id = ?",
                    Long.parseLong(key.substring("order-".length())));
            accepted(exchange);
        });

        OrderRejectedException e = rejected(buyer, "k1");

        long id = orderIdOf(buyer);
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals(ErrorCode.ORDER_EXPIRED, e.getBody().code());
        assertEquals(OrderCreationService.EXPIRED_MESSAGE, e.getBody().message());
        assertEquals("ORDER_EXPIRED", order(id).get("status"));
        assertNull(order(id).get("payment_ref"));
        assertTrue(outboxPayloads(id).isEmpty());
    }

    // ---- replays and resume ----

    @Test
    void aReplayWhilePendingPaymentIs200WithTheSameBodyAndNoNewCall() {
        long buyer = nextBuyer++;
        CreateOrderResult first = service.create(buyer, "k1", ONE_ITEM);

        CreateOrderResult again = service.create(buyer, "k1", ONE_ITEM);

        assertFalse(again.created());
        assertEquals(first.body(), again.body()); // id, status, amount, paymentRef, expiresAt, all from the row
        assertEquals(1, KEYS.size());
    }

    @Test
    void aReplayWhilePaymentUnavailableReplaysThe503WithNoNewCall() {
        long buyer = nextBuyer++;
        HANDLER.set((exchange, call) -> respond(exchange, 500, "{}"));
        rejected(buyer, "k1");
        HANDLER.set((exchange, call) -> accepted(exchange)); // the gateway is back; the old key must not use it

        OrderRejectedException again = rejected(buyer, "k1");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, again.getStatus());
        assertEquals(ErrorCode.PAYMENT_UNAVAILABLE, again.getBody().code());
        assertNull(again.getBody().reason());
        assertEquals(2, KEYS.size()); // only the first request's two attempts
        assertEquals("PAYMENT_UNAVAILABLE", order(orderIdOf(buyer)).get("status"));
    }

    /**
     * The first request crashed after setting the amount (and maybe after the
     * gateway created the payment, before the CAS). The retry resumes: no item
     * is left to reserve, the amount CAS is a no-op, initiate runs again with
     * the SAME key, the gateway returns the same ref, and the CAS moves the
     * order to PENDING_PAYMENT.
     */
    @Test
    void aRetryAfterACrashWithTheAmountSetEndsPendingPaymentWithTheSameKey() {
        long buyer = nextBuyer++;
        long id = transaction.insert(buyer, SALE, "k1", List.of(new OrderLine(11L, 1)));
        transaction.priceItem(id, 11L, "Product", new BigDecimal("10.00"));
        assertEquals(1, transaction.setAmountFromItems(id));

        CreateOrderResult retry = service.create(buyer, "k1", ONE_ITEM);

        assertTrue(retry.created()); // its CAS won
        assertEquals("PENDING_PAYMENT", retry.body().status());
        assertEquals("pay_order-" + id, retry.body().paymentRef());
        assertEquals(List.of("order-" + id), KEYS);
        assertEquals("PENDING_PAYMENT", order(id).get("status"));
        verifyNoInteractions(inventory); // nothing left to reserve
    }
}
