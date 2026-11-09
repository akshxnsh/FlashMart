package com.flashmart.inventory.warmup;

import com.flashmart.inventory.dto.reserve.ReserveRequest;
import com.flashmart.inventory.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StartupWarmup} on real Postgres. NOT {@code @Transactional}: the
 * warm-up opens its own transactions, and a test transaction around it would
 * hide a missing rollback. The warm-up is built by hand here (the Spring bean is
 * switched off in the test config), so each case chooses its own collaborators.
 */
@SpringBootTest
class StartupWarmupTest {

    private static final long BRAND_ID = 9103L;
    private static final int CALLS = 20;

    @Autowired
    private ReservationService reservations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private final List<Long> saleIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        itemIds.forEach(id -> jdbc.update("DELETE FROM flash_sale_items WHERE id = ?", id));
        saleIds.forEach(id -> jdbc.update("DELETE FROM flash_sales WHERE id = ?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
    }

    @Test
    void theRealReservePathRunsAndNothingOfItCommits() {
        liveItem(100);
        Map<String, Object> before = snapshot();

        StartupWarmup.Result result = warmup(true, CALLS, 10_000, reservations).run();

        assertEquals(StartupWarmup.Status.DONE, result.status());
        assertEquals(CALLS, result.completedCalls());
        // Native SQL, not the entities: stock, versions, reservation rows and their quantity.
        Map<String, Object> after = snapshot();
        assertEquals(before.get("reservation_rows"), after.get("reservation_rows"));
        assertEquals(before.get("reserved_units"), after.get("reserved_units"));
        assertEquals(before.get("available_stock"), after.get("available_stock"));
        assertEquals(before.get("versions"), after.get("versions"));
        assertEquals(before.get("items"), after.get("items"));
        // The path really ran (so "unchanged" is not just "never executed"): each call inserted
        // a reservation, which burned one id. That gap is the one lasting trace of a start.
        assertEquals(CALLS, (Long) after.get("sequence") - (Long) before.get("sequence"));
    }

    @Test
    void anEmptyDatabaseIsSkippedWithoutCallingReserve() {
        JdbcTemplate empty = mock(JdbcTemplate.class);
        when(empty.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        ReservationService neverCalled = mock(ReservationService.class);

        StartupWarmup.Result result = new StartupWarmup(true, CALLS, 10_000, neverCalled, empty, txManager).run();

        assertEquals(StartupWarmup.Status.SKIPPED_NO_ITEM, result.status());
        verify(neverCalled, never()).reserve(any());
    }

    @Test
    void aFailingWarmupDoesNotThrowAndStopsAtTheFirstFailure() {
        liveItem(100);
        ReservationService failing = mock(ReservationService.class);
        when(failing.reserve(any())).thenThrow(new IllegalStateException("boom"));

        StartupWarmup.Result result = warmup(true, CALLS, 10_000, failing).run(); // must not throw

        assertEquals(StartupWarmup.Status.FAILED, result.status());
        assertEquals(0, result.completedCalls());
    }

    @Test
    void aHungWarmupIsCutOffAtTheTimeout() {
        liveItem(100);
        ReservationService hung = mock(ReservationService.class);
        when(hung.reserve(any())).thenAnswer(invocation -> {
            Thread.sleep(60_000); // the daemon thread is interrupted by the cancel
            return null;
        });

        long start = System.nanoTime();
        StartupWarmup.Result result = warmup(true, CALLS, 1_000, hung).run();
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(StartupWarmup.Status.TIMED_OUT, result.status());
        assertTrue(tookMs >= 900 && tookMs < 5_000, "took " + tookMs + " ms");
    }

    @Test
    void disabledDoesNothing() {
        ReservationService neverCalled = mock(ReservationService.class);

        StartupWarmup.Result result = warmup(false, CALLS, 10_000, neverCalled).run();

        assertEquals(StartupWarmup.Status.DISABLED, result.status());
        verify(neverCalled, never()).reserve(any(ReserveRequest.class));
    }

    private StartupWarmup warmup(boolean enabled, int calls, long timeoutMs, ReservationService service) {
        return new StartupWarmup(enabled, calls, timeoutMs, service, jdbc, txManager);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM stock_reservations) AS reservation_rows,
                       (SELECT coalesce(sum(quantity), 0) FROM stock_reservations) AS reserved_units,
                       (SELECT coalesce(sum(available_stock), 0) FROM flash_sale_items) AS available_stock,
                       (SELECT coalesce(sum(version), 0) FROM flash_sale_items) AS versions,
                       (SELECT count(*) FROM flash_sale_items) AS items
                """);
        // last_value of the id sequence; is_called false means nothing was ever drawn.
        Long sequence = jdbc.queryForObject("""
                SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END
                FROM stock_reservations_id_seq
                """, Long.class);
        Map<String, Object> out = new java.util.HashMap<>(m);
        out.put("sequence", sequence);
        return out;
    }

    private void liveItem(int stock) {
        Long productId = jdbc.queryForObject("INSERT INTO products (brand_id, name) VALUES (?, ?) RETURNING id",
                Long.class, BRAND_ID, "Warmup Product " + UUID.randomUUID());
        productIds.add(productId);
        Long saleId = jdbc.queryForObject("INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) "
                        + "VALUES (?, ?, now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE') RETURNING id",
                Long.class, BRAND_ID, "Warmup Sale " + UUID.randomUUID());
        saleIds.add(saleId);
        Long itemId = jdbc.queryForObject("INSERT INTO flash_sale_items (flash_sale_id, product_id, sale_price, "
                + "total_stock, available_stock) VALUES (?, ?, 3999.00, ?, ?) RETURNING id", Long.class,
                saleId, productId, stock, stock);
        itemIds.add(itemId);
    }
}
