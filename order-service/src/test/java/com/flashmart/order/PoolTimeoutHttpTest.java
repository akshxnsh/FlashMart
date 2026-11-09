package com.flashmart.order;

import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.client.payment.PaymentCalls;
import com.flashmart.order.sweep.ExpirySweep;
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
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A borrow that times out (decisions.md, "Day 9, Part F"), forced with a pool of
 * TWO connections (Flyway needs two at start-up) and the test holding both. Real HTTP into Order. The buyer must
 * get {@code 503 SERVICE_BUSY} with the usual body (never the default 500, no
 * internals), the order must stay {@code CREATED}, and the expiry sweep must end
 * it and write the {@code OrderCancelled} that makes Inventory release the stock.
 *
 * <p>NOT tested here: Inventory actually releasing (Inventory is a mock; its
 * consumer is tested in inventory-service), and the accepted residual case of
 * stacked waits: a wait long enough for the sweep to expire the order BEFORE a
 * late reserve commits, then a failed borrow before the Version A republish.
 * Buyers 14100..14199.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=" + PoolTimeoutHttpTest.POOL,
        "spring.datasource.hikari.connection-timeout=250"}) // Hikari's minimum
class PoolTimeoutHttpTest {

    static final int POOL = 2;
    private static final long FROM = 14100, TO = 14199;
    private static final String BODY = "{\"flashSaleId\":7,\"items\":[{\"productId\":11,\"quantity\":1}]}";

    @LocalServerPort
    int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExpirySweep sweep;

    @MockitoBean
    private InventoryCalls inventory;

    @MockitoBean
    private PaymentCalls payments;

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    @Test
    void aBorrowThatTimesOutAfterReserveAnswers503AndTheSweepCancelsTheOrder() throws Exception {
        AtomicReference<List<Connection>> held = new AtomicReference<>();
        doAnswer(inv -> {
            // The request thread holds nothing now (open-in-view off), so this
            // takes every connection; the next borrow (priceItem) must time out.
            held.set(holdAll());
            return new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product");
        }).when(inventory).reserve(any());

        HttpResponse<String> response;
        try {
            response = post(FROM + 1, "busy-1");
        } finally {
            assertNotNull(held.get(), "reserve was never reached");
            release(held.get());
        }

        assertEquals(503, response.statusCode(), response.body());
        // Exactly the usual body and nothing else: no trace, no SQL, no pool name.
        assertEquals("{\"code\":\"SERVICE_BUSY\",\"message\":\"the service is busy; retry with the same "
                + "Idempotency-Key\"}", response.body());
        verify(payments, never()).initiate(anyLong(), any());

        long id = jdbc.queryForObject("select id from orders where buyer_id = ?", Long.class, FROM + 1);
        assertEquals("CREATED", status(id));
        assertEquals(0L, jdbc.queryForObject("select count(*) from order_items where order_id = ? "
                + "and unit_price is not null", Long.class, id)); // priceItem never ran

        // The deadline passes; the sweep (every 30 s outside tests) ends the order.
        jdbc.update("update orders set expires_at = clock_timestamp() - interval '1 second' where id = ?", id);
        sweep.runOnce();

        assertEquals("ORDER_EXPIRED", status(id));
        assertEquals(1L, jdbc.queryForObject("select count(*) from outbox where aggregate_id = ? "
                + "and event_type = 'OrderCancelled' and payload_json->>'reason' = 'ORDER_EXPIRED'",
                Long.class, id));
    }

    @Test
    void aBorrowThatTimesOutBeforeTheInsertAnswers503AndLeavesNoOrder() throws Exception {
        HttpResponse<String> response;
        List<Connection> held = holdAll();
        try {
            response = post(FROM + 2, "busy-2");
        } finally {
            release(held);
        }

        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"SERVICE_BUSY\""), response.body());
        assertFalse(response.body().contains("Exception"), response.body());
        assertEquals(0L, jdbc.queryForObject("select count(*) from orders where buyer_id = ?",
                Long.class, FROM + 2));
        verify(inventory, never()).reserve(any());
    }

    private List<Connection> holdAll() throws java.sql.SQLException {
        List<Connection> all = new java.util.ArrayList<>();
        for (int i = 0; i < POOL; i++) {
            all.add(dataSource.getConnection());
        }
        return all;
    }

    private static void release(List<Connection> connections) throws java.sql.SQLException {
        for (Connection c : connections) {
            c.close();
        }
    }

    private String status(long id) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, id);
    }

    private HttpResponse<String> post(long buyer, String key) throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .header("X-Buyer-Id", Long.toString(buyer))
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Signature", TestGatewayHeaders.signature(Long.toString(buyer), ts))
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
