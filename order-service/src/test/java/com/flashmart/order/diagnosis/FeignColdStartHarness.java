package com.flashmart.order.diagnosis;

import com.flashmart.order.client.inventory.InventoryClient;
import com.flashmart.order.client.inventory.ReserveRequest;
import com.flashmart.order.client.payment.InitiatePaymentRequest;
import com.flashmart.order.client.payment.PaymentGatewayClient;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.cloud.openfeign.support.FeignHttpMessageConverters;
import org.springframework.cloud.openfeign.support.HttpMessageConverterCustomizer;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DIAGNOSIS HARNESS, not a test and not production code (no @Test, so the
 * suite does not run it). Reproduces the cold-start Feign error
 * "no suitable HttpMessageConverter found for request type [...]" seen in
 * Order on the first concurrent burst (docs/testing.md, Part D R1 and R2F).
 *
 * <p>Each round builds a fresh Spring context holding only Order's two real
 * Feign clients (same interfaces, same per-client config classes, same
 * Jackson / HTTP-converter / Feign auto-configuration), points them at a JDK
 * HTTP stub, and fires CONCURRENCY first calls at once, half through each
 * client. Run it in a fresh JVM per round (rounds=1) or many rounds per JVM.
 *
 * <p>Args: mode rounds [concurrency]
 * <ul>
 *   <li>{@code burst}: the burst is the very first use of both clients.</li>
 *   <li>{@code warm}: one sequential call per client first, then the burst.</li>
 *   <li>{@code direct}: no HTTP; races {@code FeignHttpMessageConverters.getConverters()}
 *       on a fresh instance and records the list sizes the racing threads saw.</li>
 * </ul>
 */
public final class FeignColdStartHarness {

    private static final String SUITABLE = "no suitable HttpMessageConverter";

    // Deliberately NOT @Configuration / @SpringBootConfiguration: Order's
    // @SpringBootApplication component-scans test classes too, and a second
    // @EnableFeignClients there registers the clients twice and breaks every
    // @SpringBootTest (seen once: 305 context errors). Passed to
    // SpringApplicationBuilder as a plain source, its imports still apply.
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            FeignAutoConfiguration.class})
    @EnableFeignClients(clients = {InventoryClient.class, PaymentGatewayClient.class})
    static class Harness {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "burst";
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 50;

        HttpServer stub = startStub();
        String url = "http://127.0.0.1:" + stub.getAddress().getPort();
        int failedRounds = 0;
        Map<String, Integer> messages = new TreeMap<>();
        try {
            for (int round = 1; round <= rounds; round++) {
                try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Harness.class)
                        .web(WebApplicationType.NONE)
                        .properties("spring.main.banner-mode=off", "spring.cloud.config.enabled=false")
                        // Command-line args: they outrank Order's application.yml on the classpath.
                        .run("--inventory.base-url=" + url, "--payment-gateway.base-url=" + url)) {
                    RoundResult r = "direct".equals(mode) ? direct(ctx, concurrency) : burst(ctx, mode, concurrency);
                    r.messages.forEach((k, v) -> messages.merge(k, v, Integer::sum));
                    if (r.failed > 0) {
                        failedRounds++;
                    }
                    System.out.printf("ROUND %d mode=%s failed=%d/%d %s%n", round, mode, r.failed, concurrency,
                            r.detail);
                }
            }
        } finally {
            stub.stop(0);
        }
        System.out.printf("SUMMARY mode=%s rounds=%d failedRounds=%d messages=%s%n",
                mode, rounds, failedRounds, messages);
        System.exit(0);
    }

    record RoundResult(int failed, String detail, Map<String, Integer> messages) {
    }

    private static RoundResult burst(ConfigurableApplicationContext ctx, String mode, int concurrency)
            throws Exception {
        InventoryClient inventory = ctx.getBean(InventoryClient.class);
        PaymentGatewayClient payment = ctx.getBean(PaymentGatewayClient.class);
        if ("warm".equals(mode)) {
            inventory.reserve(new ReserveRequest(0L, 1L, 1L, 1));
            payment.initiate("warm", new InitiatePaymentRequest(0L, new BigDecimal("1.00"), "INR"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger failedInventory = new AtomicInteger();
        AtomicInteger failedPayment = new AtomicInteger();
        Map<String, Integer> messages = new ConcurrentHashMap<>();
        AtomicInteger stacksPrinted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            final long id = i + 1;
            final boolean useInventory = i % 2 == 0;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (useInventory) {
                        inventory.reserve(new ReserveRequest(id, 1L, 1L, 1));
                    } else {
                        payment.initiate("k" + id, new InitiatePaymentRequest(id, new BigDecimal("10.00"), "INR"));
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    (useInventory ? failedInventory : failedPayment).incrementAndGet();
                    String msg = String.valueOf(e.getMessage());
                    String key = e.getClass().getSimpleName() + ": "
                            + (msg.contains(SUITABLE) ? msg.replaceAll("\\[com\\.flashmart\\.order\\.client\\.\\w+\\.", "[")
                            : msg);
                    messages.merge(key, 1, Integer::sum);
                    if (stacksPrinted.getAndIncrement() == 0) {
                        System.out.println("FIRST FAILURE STACK (thread " + Thread.currentThread().getName() + "):");
                        e.printStackTrace(System.out);
                    }
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        return new RoundResult(failed.get(),
                "inventoryFailed=" + failedInventory.get() + " paymentFailed=" + failedPayment.get() + " " + messages,
                messages);
    }

    /** Races getConverters() on a fresh instance built from the context's own customizers. */
    private static RoundResult direct(ConfigurableApplicationContext ctx, int concurrency) throws Exception {
        ObjectProvider<ClientHttpMessageConvertersCustomizer> customizers =
                ctx.getBeanProvider(ClientHttpMessageConvertersCustomizer.class);
        ObjectProvider<HttpMessageConverterCustomizer> cloudCustomizers =
                ctx.getBeanProvider(HttpMessageConverterCustomizer.class);
        int full = new FeignHttpMessageConverters(customizers, cloudCustomizers).getConverters().size();

        FeignHttpMessageConverters fresh = new FeignHttpMessageConverters(customizers, cloudCustomizers);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        Map<String, Integer> seen = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                String key;
                try {
                    // Count what an encoder iterating the list would see right now.
                    int n = 0;
                    for (Object ignored : fresh.getConverters()) {
                        n++;
                    }
                    key = "size=" + n;
                } catch (Exception e) {
                    key = e.getClass().getSimpleName();
                }
                seen.merge(key, 1, Integer::sum);
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        int bad = seen.entrySet().stream()
                .filter(e -> !e.getKey().equals("size=" + full))
                .mapToInt(Map.Entry::getValue).sum();
        return new RoundResult(bad, "fullSize=" + full + " seen=" + new TreeMap<>(seen), Map.of());
    }

    private static HttpServer startStub() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 200);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String json = path.endsWith("/reserve")
                    ? "{\"outcome\":\"OUT_OF_STOCK\"}"
                    : "{\"paymentRef\":\"pay_1\",\"orderId\":1,\"amount\":\"10.00\",\"status\":\"CREATED\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private FeignColdStartHarness() {
    }
}
