package com.flashmart.gateway;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A real HTTP server on loopback standing in for Identity, Order or Inventory.
 * It records every request exactly as it arrived (method, raw path, headers,
 * body bytes) and answers {@code 200 {"stub":"<name>"}}, so a test can assert
 * what the Gateway forwarded, and that it forwarded nothing.
 */
public final class StubDownstream implements AutoCloseable {

    public record Received(String method, String rawPath, Headers headers, byte[] body) {
        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final String name;
    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public StubDownstream(String name) {
        this.name = name;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders(), body));
            byte[] answer = ("{\"stub\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public String uri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public Received only() {
        List<Received> all = received();
        if (all.size() != 1) {
            throw new AssertionError(name + " received " + all.size() + " requests, expected exactly 1");
        }
        return all.getFirst();
    }

    public void clear() {
        received.clear();
    }

    public String name() {
        return name;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
