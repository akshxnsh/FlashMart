package com.flashmart.order.controller;

import com.flashmart.order.TestRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code GET /internal/orders/{id}/status} over real HTTP: every status as
 * stored, 404 for an unknown id, and no Gateway signature needed (the signed-
 * identity filter covers {@code /api/orders/**} only). Buyers 14200..14299.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalOrderStatusControllerTest {

    private static final long FROM = 14200, TO = 14299;

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    @Test
    void everyStoredStatusIsReturnedAsIs() throws Exception {
        String[] statuses = {"CREATED", "PENDING_PAYMENT", "PAID", "RESERVATION_FAILED", "PAYMENT_UNAVAILABLE",
                "ORDER_EXPIRED", "PAYMENT_FAILED"};
        for (int i = 0; i < statuses.length; i++) {
            long id = insert(FROM + i, statuses[i]);
            HttpResponse<String> r = get("/internal/orders/" + id + "/status");
            assertEquals(200, r.statusCode(), statuses[i]);
            assertEquals("{\"orderId\":" + id + ",\"status\":\"" + statuses[i] + "\"}", r.body());
        }
    }

    @Test
    void anUnknownOrderIs404() throws Exception {
        long unknown = jdbc.queryForObject("select coalesce(max(id), 0) + 1000000 from orders", Long.class);
        assertEquals(404, get("/internal/orders/" + unknown + "/status").statusCode());
    }

    /**
     * A row in any status, written with native SQL. The CHECK constraints need a
     * PENDING_PAYMENT / PAID row to carry a payment_ref and amount, and a failed
     * row to carry a failure_detail; this fills what each one requires.
     */
    private long insert(long buyer, String status) {
        boolean paid = status.equals("PENDING_PAYMENT") || status.equals("PAID") || status.equals("PAYMENT_FAILED");
        return jdbc.queryForObject("insert into orders (buyer_id, flash_sale_id, status, idempotency_key, "
                        + "expires_at, amount, payment_ref, failure_detail) values (?, 7, ?, 'k', "
                        + "now() + interval '1 minute', ?, ?, ?) returning id", Long.class,
                buyer, status, paid ? new java.math.BigDecimal("59.00") : null,
                paid ? "pay_" + buyer : null,
                status.equals("CREATED") || paid ? null : "test");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
