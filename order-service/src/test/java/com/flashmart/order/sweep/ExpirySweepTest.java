package com.flashmart.order.sweep;

import com.flashmart.order.TestRows;
import com.flashmart.order.entity.OrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One sweep pass on real Postgres, single-threaded (Day 7, point 6). Races are
 * in {@link ExpirySweepRaceTest} and {@code PaymentWebhookRaceTest}.
 *
 * <p>What this cannot see: the {@code @Scheduled} timer (off in tests; passes
 * are called by hand), two processes, Kafka (the tests stop at the outbox row;
 * Inventory consuming it is tested in inventory-service), and timing at the
 * deadline itself (rows are minutes past due or an hour before).
 *
 * <p>NOT {@code @Transactional}: each order's transaction must really commit.
 * Buyers 12500..12999 belong to this class. The sweep is global, so the class
 * refuses to run while the database holds anyone else's due live orders.
 */
@SpringBootTest
class ExpirySweepTest {

    private static final long FROM = 12500, TO = 12999;

    @Autowired
    private ExpirySweep sweep;

    @Autowired
    private ExpiryTransaction transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ObjectMapper json;

    private SweepRows rows;
    private long nextBuyer = FROM;

    @BeforeEach
    void setUp() {
        rows = new SweepRows(jdbc);
        rows.assertNoForeignDueOrders(FROM, TO);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DROP TRIGGER IF EXISTS trg_test_fail_outbox ON outbox");
        jdbc.update("DROP FUNCTION IF EXISTS test_fail_outbox()");
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    private long order(OrderStatus status, String expiresIn) {
        return rows.order(nextBuyer++, status, expiresIn);
    }

    @Test
    void dueCreatedAndPendingPaymentExpireWithExactlyOneOrderCancelledEach() {
        long created = order(OrderStatus.CREATED, "-1 minute");
        long pending = order(OrderStatus.PENDING_PAYMENT, "-1 minute");

        ExpirySweep.Result result = sweep.runOnce();

        assertEquals(new ExpirySweep.Result(2, 2, 0), result);
        for (long id : List.of(created, pending)) {
            assertEquals("ORDER_EXPIRED", rows.status(id));
            assertEquals(1, rows.cancelled(id));
            assertEquals(1, rows.outboxAll(id), "no other event");
            JsonNode payload = json.readTree(jdbc.queryForObject(
                    "SELECT payload_json::text FROM outbox WHERE aggregate_id = ?", String.class, id));
            assertEquals(id, payload.get("order_id").longValue());
            assertEquals("ORDER_EXPIRED", payload.get("reason").stringValue());
            assertEquals(jdbc.queryForObject("SELECT buyer_id FROM orders WHERE id = ?", Long.class, id),
                    payload.get("buyer_id").longValue());
        }
        assertEquals(new ExpirySweep.Result(0, 0, 0), sweep.runOnce(), "a second pass finds nothing");
        assertEquals(1, rows.cancelled(created));
    }

    @Test
    void ordersNotDueAndTerminalOrdersAreUntouched() {
        Map<Long, String> untouched = new java.util.LinkedHashMap<>();
        untouched.put(order(OrderStatus.CREATED, "+1 hour"), "CREATED");
        untouched.put(order(OrderStatus.PENDING_PAYMENT, "+1 hour"), "PENDING_PAYMENT");
        // Terminal rows with a deadline long past: only the status keeps the sweep away.
        for (OrderStatus terminal : List.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.ORDER_EXPIRED,
                OrderStatus.PAYMENT_UNAVAILABLE, OrderStatus.RESERVATION_FAILED)) {
            untouched.put(order(terminal, "-1 day"), terminal.name());
        }

        assertEquals(new ExpirySweep.Result(0, 0, 0), sweep.runOnce());

        untouched.forEach((id, status) -> {
            assertEquals(status, rows.status(id));
            assertEquals(0, rows.outboxAll(id), status + " must get no event");
        });
    }

    @Test
    void theBatchLimitIsRespectedOldestFirstAndLeftoversWaitForTheNextPass() {
        List<Long> ids = new ArrayList<>();
        for (int minutesAgo = 5; minutesAgo >= 1; minutesAgo--) {
            ids.add(order(OrderStatus.CREATED, "-" + minutesAgo + " minutes")); // ids.get(0) is the oldest
        }
        ExpirySweep small = new ExpirySweep(jdbc, transaction, 2);

        assertEquals(new ExpirySweep.Result(2, 2, 0), small.runOnce());
        assertEquals(List.of("ORDER_EXPIRED", "ORDER_EXPIRED", "CREATED", "CREATED", "CREATED"), statuses(ids),
                "the two oldest go first");
        assertEquals(new ExpirySweep.Result(2, 2, 0), small.runOnce());
        assertEquals(new ExpirySweep.Result(1, 1, 0), small.runOnce());
        assertEquals(new ExpirySweep.Result(0, 0, 0), small.runOnce());
        ids.forEach(id -> assertEquals(1, rows.cancelled(id)));
    }

    // The outbox insert fails for ONE order (a trigger raises for its id). Its
    // transaction rolls back whole (status kept, no event); the others in the
    // same pass still expire; the next pass picks it up again.
    @Test
    void oneFailingOrderDoesNotBlockTheOthersAndIsRetriedNextPass() {
        long first = order(OrderStatus.CREATED, "-3 minutes");
        long bad = order(OrderStatus.PENDING_PAYMENT, "-2 minutes");
        long last = order(OrderStatus.CREATED, "-1 minute");
        jdbc.update("CREATE FUNCTION test_fail_outbox() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'injected outbox failure'; END $$ LANGUAGE plpgsql");
        jdbc.update("CREATE TRIGGER trg_test_fail_outbox BEFORE INSERT ON outbox FOR EACH ROW "
                + "WHEN (NEW.aggregate_id = " + bad + ") EXECUTE FUNCTION test_fail_outbox()");

        assertEquals(new ExpirySweep.Result(3, 2, 1), sweep.runOnce());
        assertEquals("ORDER_EXPIRED", rows.status(first));
        assertEquals("ORDER_EXPIRED", rows.status(last), "the order after the failing one still ran");
        assertEquals("PENDING_PAYMENT", rows.status(bad), "the failed transaction rolled back, CAS included");
        assertEquals(0, rows.outboxAll(bad));

        jdbc.update("DROP TRIGGER trg_test_fail_outbox ON outbox");
        assertEquals(new ExpirySweep.Result(1, 1, 0), sweep.runOnce());
        assertEquals("ORDER_EXPIRED", rows.status(bad));
        assertEquals(1, rows.cancelled(bad));
    }

    // The real plan of the sweep SELECT over thousands of terminal rows and a
    // few live ones, in a transaction that is rolled back (rows and ANALYZE
    // statistics included). Printed, not asserted: the planner may legitimately
    // pick a seq scan on a small table. The second plan, with seq scans
    // discouraged, proves only that the partial index CAN serve this query,
    // i.e. that the literal WHERE matches the index predicate. No timing is
    // measured here, so no speedup is claimed.
    @Test
    void explainTheSweepSelect() {
        tx.executeWithoutResult(s -> {
            // One buyer per row (uk_orders_buyer_sale_live_or_paid allows one PAID per
            // buyer and sale). The ids are never committed: this transaction rolls back.
            jdbc.update("INSERT INTO orders (buyer_id, flash_sale_id, status, idempotency_key, expires_at, amount, "
                    + "payment_ref) SELECT 900000000 + g, ?, (ARRAY['PAID','ORDER_EXPIRED','PAYMENT_FAILED','RESERVATION_FAILED'])"
                    + "[1 + g % 4], 'explain-' || g, clock_timestamp() - interval '1 day', 499.00, "
                    + "CASE WHEN g % 4 IN (0, 1, 2) THEN 'pay_explain_' || g END FROM generate_series(1, 5000) g",
                    SweepRows.SALE);
            for (int i = 0; i < 5; i++) {
                order(OrderStatus.CREATED, "-1 minute");
            }
            jdbc.execute("ANALYZE orders");
            String sql = ExpirySweep.DUE_ORDERS.replace("LIMIT ?", "LIMIT 100");
            Long total = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
            String natural = plan(sql);
            jdbc.execute("SET LOCAL enable_seqscan = off");
            String forced = plan(sql);
            System.out.println("EXPLAIN sweep SELECT, orders rows visible = " + total + "\n-- planner's choice:\n"
                    + natural + "\n-- with enable_seqscan = off:\n" + forced);
            assertTrue(forced.contains("idx_orders_expiry_sweep_live"),
                    "the partial index cannot serve the sweep SELECT; the WHERE does not imply its predicate:\n" + forced);
            s.setRollbackOnly();
        });
    }

    private String plan(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }

    private List<String> statuses(List<Long> ids) {
        return ids.stream().map(rows::status).toList();
    }
}
