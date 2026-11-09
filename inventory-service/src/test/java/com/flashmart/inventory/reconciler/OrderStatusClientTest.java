package com.flashmart.inventory.reconciler;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OrderStatusClient} against a JDK HTTP server standing in for Order:
 * every outcome becomes an {@link OrderStatusClient.Answer}, nothing throws.
 * The read timeout is 300 ms here so the slow case is quick.
 */
class OrderStatusClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aStatusIsReturnedAsOrderSpellsIt() throws Exception {
        start(200, "{\"orderId\":42,\"status\":\"ORDER_EXPIRED\"}", 0);
        assertEquals(new OrderStatusClient.Answer.Status("ORDER_EXPIRED"), client().statusOf(42));
    }

    @Test
    void a404IsNotFound() throws Exception {
        start(404, "", 0);
        assertInstanceOf(OrderStatusClient.Answer.NotFound.class, client().statusOf(42));
    }

    @Test
    void a5xxASlowAnswerARefusalOrAnotherOrdersBodyAreUnreachable() throws Exception {
        start(500, "{}", 0);
        assertInstanceOf(OrderStatusClient.Answer.Unreachable.class, client().statusOf(42));
        stop();

        start(200, "{\"orderId\":42,\"status\":\"PAID\"}", 1_000);
        long t0 = System.nanoTime();
        OrderStatusClient.Answer slow = client().statusOf(42);
        assertInstanceOf(OrderStatusClient.Answer.Unreachable.class, slow);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 900, "the read timeout cut it short");
        stop();

        start(200, "{\"orderId\":43,\"status\":\"PAID\"}", 0);
        assertInstanceOf(OrderStatusClient.Answer.Unreachable.class, client().statusOf(42),
                "an answer about another order is never acted on");
        stop();
        server = null;

        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        assertInstanceOf(OrderStatusClient.Answer.Unreachable.class, client(closed).statusOf(42));
    }

    private void start(int status, String body, long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/orders/", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    private OrderStatusClient client() {
        return client(server.getAddress().getPort());
    }

    private static OrderStatusClient client(int port) {
        return new OrderStatusClient(new ReconcilerSettings(50, 0, 0, 0, 0, "http://127.0.0.1:" + port, 500, 300));
    }
}
