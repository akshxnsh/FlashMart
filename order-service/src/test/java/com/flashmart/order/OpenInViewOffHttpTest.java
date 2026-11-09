package com.flashmart.order;

import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.client.payment.InitiatePaymentResponse;
import com.flashmart.order.client.payment.PaymentCalls;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * {@code spring.jpa.open-in-view: false} seen from where it matters: a REAL HTTP
 * request into Order (decisions.md, "Day 9, Part F"). With open-in-view on, the
 * web request keeps the database session, and with it one pooled connection,
 * from the first transaction to the end of the request, so the connection was
 * held while Order waited on Inventory and on the payment gateway.
 * {@link com.flashmart.order.service.OrderTransactionBoundaryTest} calls the
 * service directly and cannot see that.
 *
 * <p>The two outbound calls are mocks that sleep (a slow Inventory, a slow
 * gateway) and read the pool's active count while they are "in flight". The
 * inbound side, where open-in-view lives, is the real servlet stack. Nothing
 * else borrows in tests (relay and sweep are off). Buyers 14000..14099.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenInViewOffHttpTest {

    private static final long FROM = 14000, TO = 14099;
    private static final long SLOW_MS = 300;

    @LocalServerPort
    int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryCalls inventory;

    @MockitoBean
    private PaymentCalls payments;

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    @Test
    void noPooledConnectionIsHeldWhileAnHttpRequestWaitsOnInventoryOrThePaymentGateway() throws Exception {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        List<Integer> activeDuringReserve = new CopyOnWriteArrayList<>();
        List<Integer> activeDuringInitiate = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            Thread.sleep(SLOW_MS);
            activeDuringReserve.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product");
        }).when(inventory).reserve(any());
        doAnswer(inv -> {
            Thread.sleep(SLOW_MS);
            activeDuringInitiate.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return new InitiatePaymentResponse("pay_order-" + inv.getArgument(0), inv.getArgument(0),
                    inv.getArgument(1), "PENDING");
        }).when(payments).initiate(anyLong(), any());

        HttpResponse<String> response = post(FROM + 1, "osiv-1",
                "{\"flashSaleId\":7,\"items\":[{\"productId\":11,\"quantity\":1}]}");

        assertEquals(201, response.statusCode(), response.body()); // the whole path ran
        assertEquals(List.of(0), activeDuringReserve, "a pooled connection was held while Inventory was called");
        assertEquals(List.of(0), activeDuringInitiate, "a pooled connection was held while the payment was started");
    }

    private HttpResponse<String> post(long buyer, String key, String json) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Buyer-Id", Long.toString(buyer))
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Signature", TestGatewayHeaders.signature(Long.toString(buyer), ts))
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
