package com.flashmart.inventory.diagnosis;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DIAGNOSIS PROBE, not a test (no @Test; the suite does not run it). Drives a
 * RUNNING Inventory on :8082 directly (Order not involved) to see why its
 * Hikari pool filled during a cold-start warm-up (docs/testing.md, R2F).
 *
 * <p>Modes (args[0]):
 * <ul>
 *   <li>{@code warm N baseOrderId}: N sequential reserve calls, then exit.</li>
 *   <li>{@code burst N baseOrderId}: N concurrent reserve calls released at once
 *       (latch), while a sampler records every 250 ms: pg_stat_activity of
 *       Inventory's database (state, wait_event_type, wait_event, short query)
 *       over ONE dedicated JDBC connection, and Hikari active / pending from
 *       /actuator/prometheus.</li>
 * </ul>
 * All calls go to ONE item (flashSaleId 1, productId 2 = item 2, 50 units),
 * quantity 1, distinct order ids. The HTTP timeout is 30 s, so latencies past
 * Order's 2 s read limit are seen rather than cut off.
 *
 * <p>Needs DB_USERNAME / DB_PASSWORD in the environment.
 */
public final class ReserveColdStartProbe {

    private static final String BASE = "http://localhost:8082";
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/inventory-service";
    private static final Pattern HIKARI = Pattern.compile(
            "^hikaricp_connections_(active|pending|idle|max)\\{[^}]*} ([0-9.eE+-]+)$", Pattern.MULTILINE);

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int n = Integer.parseInt(args[1]);
        long baseOrderId = Long.parseLong(args[2]);
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();

        if ("warm".equals(mode)) {
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                HttpResponse<String> r = reserve(http, baseOrderId + i);
                System.out.printf("WARM %d status=%d ms=%d %s%n", i + 1, r.statusCode(),
                        (System.nanoTime() - t0) / 1_000_000, r.body());
            }
            return;
        }

        AtomicBoolean sampling = new AtomicBoolean(true);
        List<String> samples = Collections.synchronizedList(new ArrayList<>());
        long start = System.nanoTime();
        Thread sampler = new Thread(() -> sample(http, sampling, samples, start), "sampler");
        sampler.start();
        Thread.sleep(600); // a couple of idle samples before the burst

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<long[]>> futures = new ArrayList<>();
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        for (int i = 0; i < n; i++) {
            final long orderId = baseOrderId + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                long t0 = System.nanoTime();
                String key;
                try {
                    HttpResponse<String> r = reserve(http, orderId);
                    Matcher m = Pattern.compile("\"outcome\":\"(\\w+)\"").matcher(r.body());
                    key = r.statusCode() + " " + (m.find() ? m.group(1) : r.body());
                } catch (Exception e) {
                    key = e.getClass().getSimpleName();
                }
                outcomes.merge(key, 1, Integer::sum);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                long sentAt = (t0 - start) / 1_000_000;
                return new long[] {sentAt, ms};
            }));
        }
        ready.await();
        long burstAt = (System.nanoTime() - start) / 1_000_000;
        go.countDown();
        List<Long> lat = new ArrayList<>();
        for (Future<long[]> f : futures) {
            lat.add(f.get()[1]);
        }
        Thread.sleep(600);
        sampling.set(false);
        sampler.join();
        pool.shutdown();

        Collections.sort(lat);
        long over2s = lat.stream().filter(ms -> ms > 2000).count();
        System.out.println("BURST released at t=" + burstAt + " ms");
        samples.forEach(System.out::println);
        System.out.printf("RESULT n=%d outcomes=%s p50=%d p90=%d p99=%d max=%d over2s=%d%n", n,
                new TreeMap<>(outcomes), lat.get(n / 2), lat.get((int) (n * 0.9)), lat.get((int) (n * 0.99) - 1),
                lat.get(n - 1), over2s);
        System.exit(0);
    }

    private static HttpResponse<String> reserve(HttpClient http, long orderId) throws Exception {
        String body = "{\"orderId\":" + orderId + ",\"flashSaleId\":1,\"productId\":2,\"quantity\":1}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/internal/flash-sale-items/reserve"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** One line per sample: t, Hikari a/p/i, then count per (state, wait type, wait event, query). */
    /** Latest Hikari reading; scraped on its own thread because the scrape queues behind the burst in Tomcat. */
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_HIKARI =
            new java.util.concurrent.atomic.AtomicReference<>("hikari=none-yet");

    private static void sample(HttpClient http, AtomicBoolean on, List<String> out, long start) {
        Thread scraper = new Thread(() -> {
            while (on.get()) {
                long t0 = System.nanoTime();
                String h = hikari(http);
                LAST_HIKARI.set(h + " (scraped t=" + (t0 - start) / 1_000_000 + "+"
                        + (System.nanoTime() - t0) / 1_000_000 + "ms)");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "hikari-scraper");
        scraper.setDaemon(true);
        scraper.start();
        String sql = "SELECT state, coalesce(wait_event_type,'-') wt, coalesce(wait_event,'-') we, "
                // An 'idle' session is either in the pool or borrowed by a thread between
                // statements; Postgres cannot tell them apart. Group them without the query.
                + "CASE WHEN state = 'idle' THEN '' ELSE left(regexp_replace(query, '\\s+', ' ', 'g'), 50) END q, "
                + "count(*) c, "
                + "max(extract(epoch FROM now() - xact_start) * 1000)::int xact_ms "
                + "FROM pg_stat_activity WHERE datname = 'inventory-service' AND pid <> pg_backend_pid() "
                + "AND backend_type = 'client backend' "
                + "GROUP BY 1, 2, 3, 4 ORDER BY c DESC";
        try (Connection c = DriverManager.getConnection(DB_URL, System.getenv("DB_USERNAME"),
                System.getenv("DB_PASSWORD")); Statement st = c.createStatement()) {
            while (on.get()) {
                long tick = System.nanoTime();
                StringBuilder line = new StringBuilder();
                line.append("t=").append((tick - start) / 1_000_000).append(" ");
                line.append(LAST_HIKARI.get()).append(" | pg:");
                try (ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        line.append(" [").append(rs.getInt("c")).append("x ").append(rs.getString("state"))
                                .append(' ').append(rs.getString("wt")).append('/').append(rs.getString("we"))
                                .append(" xact<=").append(rs.getInt("xact_ms")).append("ms \"")
                                .append(rs.getString("q")).append("\"]");
                    }
                }
                out.add(line.toString());
                long sleep = 250 - (System.nanoTime() - tick) / 1_000_000;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            }
        } catch (Exception e) {
            out.add("SAMPLER FAILED: " + e);
        }
    }

    private static String hikari(HttpClient http) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/actuator/prometheus"))
                    .timeout(Duration.ofMillis(5000)).GET().build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();
            Map<String, String> v = new TreeMap<>();
            Matcher m = HIKARI.matcher(body);
            while (m.find()) {
                v.put(m.group(1), String.valueOf((int) Double.parseDouble(m.group(2))));
            }
            return "hikari active=" + v.get("active") + " pending=" + v.get("pending") + " idle=" + v.get("idle")
                    + " max=" + v.get("max");
        } catch (Exception e) {
            return "hikari=? (" + e.getClass().getSimpleName() + ")";
        }
    }

    private ReserveColdStartProbe() {
    }
}
