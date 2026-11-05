package com.flashmart.order.client.inventory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real Feign client, real HTTP, against a JDK {@link HttpServer} STUB that
 * plays Inventory. This tests Order's side of the wire (decoding, timeouts,
 * error mapping); it is NOT a test against the real Inventory service.
 *
 * <p>The read timeout is shortened to 300 ms here so the slow case is quick;
 * production's 2 s is the same property.
 */
@SpringBootTest
class InventoryClientHttpTest {

    private static final int READ_TIMEOUT_MS = 300;
    private static final AtomicReference<Handler> HANDLER = new AtomicReference<>();
    private static final HttpServer STUB = start();

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> HANDLER.get().handle(exchange));
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("inventory.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("inventory.reserve-timeout-ms", () -> READ_TIMEOUT_MS);
    }

    @AfterAll
    static void stop() {
        STUB.stop(0);
    }

    @Autowired
    private InventoryCalls inventory;

    @Autowired
    private ApplicationContext context;

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static ReserveRequest request() {
        return new ReserveRequest(1L, 7L, 11L, 2);
    }

    @Test
    void aReservedBodyIsDecodedWithAnExactStringPrice() {
        AtomicReference<String> sent = new AtomicReference<>();
        HANDLER.set(exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"outcome\":\"RESERVED\",\"reservationId\":5,"
                    + "\"salePrice\":\"3999.10\",\"brandId\":3,\"productName\":\"Suede Classic\"}");
        });

        ReserveResponse response = inventory.reserve(request());

        assertEquals(ReserveOutcome.RESERVED, response.outcome());
        assertEquals(new BigDecimal("3999.10"), response.salePrice()); // scale kept: equals, not compareTo
        assertEquals("Suede Classic", response.productName());
        assertTrue(sent.get().contains("\"orderId\":1") && sent.get().contains("\"quantity\":2"), sent.get());
    }

    @Test
    void aNotReservedBodyWithOmittedFieldsDecodes() {
        HANDLER.set(exchange -> respond(exchange, 200, "{\"outcome\":\"OUT_OF_STOCK\"}"));

        ReserveResponse response = inventory.reserve(request());

        assertEquals(ReserveOutcome.OUT_OF_STOCK, response.outcome());
        assertNull(response.salePrice());
    }

    @Test
    void aSlowInventoryTimesOutAtTheConfiguredReadTimeout() {
        HANDLER.set(exchange -> {
            try {
                Thread.sleep(READ_TIMEOUT_MS * 5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"outcome\":\"RESERVED\"}");
        });

        long start = System.nanoTime();
        assertThrows(InventoryUnavailableException.class, () -> inventory.reserve(request()));
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        // Fails if the read timeout were left at Feign's default (60 s) or
        // anything above 1.5 s: the stub answers after 1.5 s.
        assertTrue(tookMs >= READ_TIMEOUT_MS - 50 && tookMs < READ_TIMEOUT_MS * 4L, "took " + tookMs + " ms");
    }

    @Test
    void aNon200IsInventoryUnavailable() {
        HANDLER.set(exchange -> respond(exchange, 400, "{\"code\":\"INVALID_QUANTITY\",\"message\":\"x\"}"));

        InventoryUnavailableException e =
                assertThrows(InventoryUnavailableException.class, () -> inventory.reserve(request()));
        assertTrue(e.getMessage().contains("400"), e.getMessage());
    }

    @Test
    void aRefusedConnectionIsInventoryUnavailable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        // The Spring bean's URL is fixed per context, so this case builds a
        // second client through Spring Cloud (same encoder, decoder and
        // contract) on a closed port, and goes through the same wrapper.
        InventoryClient direct = new FeignClientBuilder(context)
                .forType(InventoryClient.class, "inventory-refused")
                .url("http://127.0.0.1:" + closedPort)
                .build();
        InventoryCalls calls = new InventoryCalls(direct);

        assertThrows(InventoryUnavailableException.class, () -> calls.reserve(request()));
    }
}
