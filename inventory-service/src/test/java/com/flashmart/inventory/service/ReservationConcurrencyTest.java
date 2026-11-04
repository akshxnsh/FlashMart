package com.flashmart.inventory.service;

import com.flashmart.inventory.dto.reserve.ReserveOutcome;
import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.dto.reserve.ReserveResponse;
import com.flashmart.inventory.repository.FlashSaleItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code reserve} under real contention, and the edges of the liveness window,
 * on real Postgres.
 *
 * <p>NOT {@code @Transactional}, for the same reason as
 * {@link ReservationServiceTest}: each reserve must really commit or roll back.
 * Data is seeded through JdbcTemplate and removed by id in foreign-key order.
 * The helpers are copied from that class on purpose, not shared.
 *
 * <p>Every race below uses a barrier: all threads are created and parked on a
 * {@code go} latch, and call {@code reserve()} only after it opens. That makes
 * the calls overlap as much as a JVM allows; it does not control the order
 * inside Postgres. Results are asserted on DB state, not only return values.
 *
 * <p>Each round is checked on its own and the problems are collected, so a
 * failing run reports how many rounds broke, not just the first one.
 *
 * <p>This is a contention test, NOT a load test: at most 50 threads, one JVM,
 * one connection pool left at its default.
 *
 * <p>{@code ReservationTransaction} is wrapped in a Mockito spy for the whole
 * class, so every test here pays the spy's (small) call overhead.
 */
@SpringBootTest
class ReservationConcurrencyTest {

    private static final long BRAND_ID = 9001L;
    private static final String PRODUCT_NAME_PREFIX = "Reserve Concurrency Product ";
    private static final int ROUNDS = 100;
    private static final long CEILING_SECONDS = 30;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FlashSaleItemRepository items;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // findReplay is called from exactly one place: the unique-violation catch
    // in ReservationServiceImpl. Its invocation count is therefore the number
    // of times that catch ran.
    @MockitoSpyBean
    private ReservationTransaction transaction;

    private final List<Long> saleIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long itemId : itemIds) {
            jdbc.update("DELETE FROM stock_reservations WHERE flash_sale_item_id = ?", itemId);
            jdbc.update("DELETE FROM flash_sale_items WHERE id = ?", itemId);
        }
        saleIds.forEach(id -> jdbc.update("DELETE FROM flash_sales WHERE id = ?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
    }

    // ---- races ----------------------------------------------------------

    // Two buyers, one unit, 100 fresh items. The loser's UPDATE blocks on the
    // winner's row lock, re-checks the WHERE after the commit, and matches 0 rows.
    @Test
    void lastUnitTwoBuyers() throws Exception {
        List<String> brokenRounds = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            Seed s = seed("-1 hour", "+1 hour", 1);
            List<Result> results = race(List.of(request(1L, s, 1), request(2L, s, 1)));

            List<String> problems = new ArrayList<>(exceptionsIn(results));
            long reserved = count(results, ReserveOutcome.RESERVED);
            long outOfStock = count(results, ReserveOutcome.OUT_OF_STOCK);
            if (reserved != 1 || outOfStock != 1) {
                problems.add("outcomes: " + reserved + " RESERVED, " + outOfStock + " OUT_OF_STOCK");
            }
            if (stock(s) != 0) {
                problems.add("stock " + stock(s) + " (want 0)");
            }
            if (version(s) != 1L) {
                problems.add("version " + version(s) + " (want 1)");
            }
            List<Map<String, Object>> rows = reservationRows(s);
            if (rows.size() != 1) {
                problems.add(rows.size() + " reservation rows (want 1)");
            } else if (reserved == 1) {
                int winner = indexOfOutcome(results, ReserveOutcome.RESERVED);
                long winnerOrder = winner + 1L;
                if (!rows.get(0).get("order_id").equals(winnerOrder)
                        || !rows.get(0).get("id").equals(results.get(winner).response().reservationId())) {
                    problems.add("the surviving row " + rows.get(0)
                            + " does not belong to the winner (order " + winnerOrder + ")");
                }
            }
            if (!problems.isEmpty()) {
                brokenRounds.add("round " + round + ": " + String.join("; ", problems));
            }
        }
        assertNoBrokenRounds("lastUnitTwoBuyers", brokenRounds);
    }

    // The same request twice at once. One of them either finds the other's row
    // at the replay check, or loses the unique-index race on the insert and
    // answers through the catch. Both must give the same RESERVED answer.
    @Test
    void sameOrderTwoThreads() throws Exception {
        List<String> brokenRounds = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            Seed s = seed("-1 hour", "+1 hour", 10);
            List<Result> results = race(List.of(request(1L, s, 2), request(1L, s, 2)));

            List<String> problems = new ArrayList<>(exceptionsIn(results));
            if (problems.isEmpty()) {
                ReserveResponse a = results.get(0).response();
                ReserveResponse b = results.get(1).response();
                if (a.outcome() != ReserveOutcome.RESERVED || b.outcome() != ReserveOutcome.RESERVED) {
                    problems.add("outcomes " + a.outcome() + " and " + b.outcome());
                }
                if (!a.equals(b)) {
                    problems.add("responses differ: " + a + " vs " + b);
                }
            }
            if (stock(s) != 8) {
                problems.add("stock " + stock(s) + " (want 8)");
            }
            if (version(s) != 1L) {
                problems.add("version " + version(s) + " (want 1)");
            }
            if (reservationRows(s).size() != 1) {
                problems.add(reservationRows(s).size() + " reservation rows (want 1)");
            }
            if (!problems.isEmpty()) {
                brokenRounds.add("round " + round + ": " + String.join("; ", problems));
            }
        }

        long catchRan = Mockito.mockingDetails(transaction).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("findReplay"))
                .count();
        System.out.println("sameOrderTwoThreads: unique-violation catch ran in "
                + catchRan + " of " + ROUNDS + " rounds");

        assertNoBrokenRounds("sameOrderTwoThreads", brokenRounds);
    }

    // 50 different buyers, 5 units, one race. Exactly 5 may win.
    @Test
    void nBuyersFixedStock() throws Exception {
        Seed s = seed("-1 hour", "+1 hour", 5);
        List<ReserveRequest> requests = new ArrayList<>();
        for (long orderId = 1; orderId <= 50; orderId++) {
            requests.add(request(orderId, s, 1));
        }

        List<Result> results = race(requests);

        List<String> problems = new ArrayList<>(exceptionsIn(results));
        long reserved = count(results, ReserveOutcome.RESERVED);
        long outOfStock = count(results, ReserveOutcome.OUT_OF_STOCK);
        if (reserved != 5 || outOfStock != 45) {
            problems.add("outcomes: " + reserved + " RESERVED, " + outOfStock + " OUT_OF_STOCK (want 5 / 45)");
        }
        if (stock(s) != 0) {
            problems.add("stock " + stock(s) + " (want 0)");
        }
        List<Map<String, Object>> rows = reservationRows(s);
        if (rows.size() != 5) {
            problems.add(rows.size() + " reservation rows (want 5)");
        }
        long units = rows.stream().mapToLong(r -> ((Number) r.get("quantity")).longValue()).sum();
        if (units != 5) {
            problems.add("sum(quantity) = " + units + " (want 5)");
        }
        assertTrue(problems.isEmpty(), () -> "nBuyersFixedStock broke: " + String.join("; ", problems));
    }

    // ---- liveness boundaries (half-open: start inclusive, end exclusive) --

    // now() is frozen inside a transaction, so T is the very value the UPDATE
    // compares against. SELECT now(), the window write and the UPDATE must all
    // run in ONE transaction, or T would not be the UPDATE's now().
    @Test
    void saleStartIsInclusive() {
        Seed s = seed("-1 hour", "+1 hour", 10);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OffsetDateTime t = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
            jdbc.update("UPDATE flash_sales SET start_time = ?, end_time = ? WHERE id = ?",
                    t, t.plusHours(1), s.saleId);

            int rows = items.decrementIfAvailableAndLive(s.itemId, 1);

            assertEquals(1, rows, "start_time = now() must count as live");
            // Uncommitted decrement visible to JdbcTemplate: same transaction.
            assertEquals(9, stock(s), "JdbcTemplate and the repository must share one transaction");
        });
    }

    @Test
    void saleEndIsExclusive() {
        Seed s = seed("-1 hour", "+1 hour", 10);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            OffsetDateTime t = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
            jdbc.update("UPDATE flash_sales SET start_time = ?, end_time = ? WHERE id = ?",
                    t.minusHours(1), t, s.saleId);

            int rows = items.decrementIfAvailableAndLive(s.itemId, 1);

            assertEquals(0, rows, "end_time = now() must NOT count as live");
            assertEquals(10, stock(s));
        });
    }

    // ---- race harness ---------------------------------------------------

    /** What one thread got: a response, or whatever reserve() threw. */
    private record Result(ReserveResponse response, Throwable error) {
    }

    /**
     * One thread per request, all parked on {@code go}. A hang fails the test
     * (30 s ceiling) instead of hanging the build.
     */
    private List<Result> race(List<ReserveRequest> requests) throws Exception {
        int threads = requests.size();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Result>> futures = new ArrayList<>();
            for (ReserveRequest request : requests) {
                Callable<Result> task = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Result(reservationService.reserve(request), null);
                    } catch (Throwable t) {
                        return new Result(null, t);
                    }
                };
                futures.add(executor.submit(task));
            }
            assertTrue(ready.await(CEILING_SECONDS, TimeUnit.SECONDS), "threads never all became ready");
            go.countDown();

            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get(CEILING_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<String> exceptionsIn(List<Result> results) {
        return results.stream()
                .filter(r -> r.error() != null)
                .map(r -> "exception " + r.error())
                .toList();
    }

    private static long count(List<Result> results, ReserveOutcome outcome) {
        return results.stream()
                .filter(r -> r.response() != null && r.response().outcome() == outcome)
                .count();
    }

    private static int indexOfOutcome(List<Result> results, ReserveOutcome outcome) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).response() != null && results.get(i).response().outcome() == outcome) {
                return i;
            }
        }
        return -1;
    }

    private static void assertNoBrokenRounds(String test, List<String> brokenRounds) {
        assertTrue(brokenRounds.isEmpty(), () -> test + ": " + brokenRounds.size() + " of " + ROUNDS
                + " rounds broke. First few:\n"
                + String.join("\n", brokenRounds.stream().limit(5).toList()));
    }

    // ---- helpers (copied from ReservationServiceTest) -------------------

    private record Seed(long saleId, long productId, long itemId, String productName) {
    }

    /** Offsets are Postgres interval text, applied to the database's now(). */
    private Seed seed(String startOffset, String endOffset, int stock) {
        String productName = PRODUCT_NAME_PREFIX + UUID.randomUUID();
        Long productId = jdbc.queryForObject(
                "INSERT INTO products (brand_id, name) VALUES (?, ?) RETURNING id",
                Long.class, BRAND_ID, productName);
        productIds.add(productId);

        Long saleId = jdbc.queryForObject(
                "INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) "
                        + "VALUES (?, ?, now() + ?::interval, now() + ?::interval, 'ACTIVE') RETURNING id",
                Long.class, BRAND_ID, "Reserve Concurrency Sale " + UUID.randomUUID(), startOffset, endOffset);
        saleIds.add(saleId);

        Long itemId = jdbc.queryForObject(
                "INSERT INTO flash_sale_items (flash_sale_id, product_id, sale_price, total_stock, available_stock) "
                        + "VALUES (?, ?, 3999.00, ?, ?) RETURNING id",
                Long.class, saleId, productId, stock, stock);
        itemIds.add(itemId);
        return new Seed(saleId, productId, itemId, productName);
    }

    private ReserveRequest request(long orderId, Seed s, int quantity) {
        return new ReserveRequest(orderId, s.saleId, s.productId, quantity);
    }

    private int stock(Seed s) {
        return jdbc.queryForObject(
                "SELECT available_stock FROM flash_sale_items WHERE id = ?", Integer.class, s.itemId);
    }

    private long version(Seed s) {
        return jdbc.queryForObject(
                "SELECT version FROM flash_sale_items WHERE id = ?", Long.class, s.itemId);
    }

    private List<Map<String, Object>> reservationRows(Seed s) {
        return jdbc.queryForList(
                "SELECT id, order_id, quantity FROM stock_reservations WHERE flash_sale_item_id = ?", s.itemId);
    }
}
