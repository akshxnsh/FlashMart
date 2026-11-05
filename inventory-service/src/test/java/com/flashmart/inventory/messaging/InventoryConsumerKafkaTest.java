package com.flashmart.inventory.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inventory's two listeners against real Postgres and an in-JVM Kafka broker
 * ({@code @EmbeddedKafka}, KRaft; Day 7, points 1 to 4).
 *
 * <p>What this CANNOT see: a separate broker process or the docker-compose
 * Kafka; a real consumer-group rebalance; a process that dies between the DB
 * commit and the offset commit (a crash is simulated by a database error that
 * rolls the transaction back, not by killing the JVM); Order's real producer
 * (messages are hand-written JSON in the shape Order's outbox holds); more than
 * one partition or one Inventory instance. Backoff is shortened to 100..200 ms
 * here; the production values are provisional and unmeasured.
 *
 * <p>NOT {@code @Transactional}: the listener runs on its own thread, so the
 * data must really be committed. Rows are seeded with JdbcTemplate and
 * deleted by id. Order ids from 870_000_000 and unique per test.
 */
@SpringBootTest(properties = {
        "kafka.bootstrap=${spring.embedded.kafka.brokers}",
        "kafka.consumers-enabled=true",
        "kafka.consumer.backoff-initial-ms=100",
        "kafka.consumer.backoff-max-ms=200"})
@EmbeddedKafka(partitions = 1, topics = {"order-cancelled", "payment-confirmed"})
class InventoryConsumerKafkaTest {

    private static final String CANCELLED = "order-cancelled", CONFIRMED = "payment-confirmed";
    private static final long BRAND_ID = 9102L;
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ORDER = new java.util.concurrent.atomic.AtomicLong(870_000_000L);

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.embedded.kafka.brokers}")
    private String brokers;

    private DefaultKafkaProducerFactory<String, String> producerFactory;
    private KafkaTemplate<String, String> kafka;
    private final List<Long> saleIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();
    private ListAppender<ILoggingEvent> configLogs, serviceLogs;

    @BeforeEach
    void setUp() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerFactory = new DefaultKafkaProducerFactory<>(props);
        kafka = new KafkaTemplate<>(producerFactory);
        configLogs = attach(InventoryKafkaConfig.class);
        serviceLogs = attach(com.flashmart.inventory.service.ReservationEventService.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DROP TRIGGER IF EXISTS trg_test_fail_inventory ON flash_sale_items");
        jdbc.update("DROP FUNCTION IF EXISTS test_fail_inventory()");
        jdbc.update("DROP SEQUENCE IF EXISTS test_fail_inventory_seq");
        detach(InventoryKafkaConfig.class, configLogs);
        detach(com.flashmart.inventory.service.ReservationEventService.class, serviceLogs);
        producerFactory.destroy();
        for (Long itemId : itemIds) {
            jdbc.update("DELETE FROM stock_reservations WHERE flash_sale_item_id = ?", itemId);
            jdbc.update("DELETE FROM flash_sale_items WHERE id = ?", itemId);
        }
        saleIds.forEach(id -> jdbc.update("DELETE FROM flash_sales WHERE id = ?", id));
        productIds.forEach(id -> jdbc.update("DELETE FROM products WHERE id = ?", id));
    }

    // ---- release ----

    @Test
    void everyOrderCancelledReasonReleasesTheReservation() throws Exception {
        List<String> reasons = List.of("RESERVATION_FAILED", "PAYMENT_UNAVAILABLE", "ORDER_EXPIRED", "PAYMENT_FAILED");
        long lastOffset = -1;
        List<long[]> cases = new ArrayList<>();
        for (String reason : reasons) {
            long item = item(100), order = order();
            reserve(order, item, 2);
            lastOffset = send(CANCELLED, order, "{\"order_id\":" + order + ",\"reason\":\"" + reason + "\",\"buyer_id\":1}");
            cases.add(new long[]{order, item});
        }
        awaitCommitted(CANCELLED, lastOffset + 1);
        for (int i = 0; i < reasons.size(); i++) {
            long[] c = cases.get(i);
            assertEquals("RELEASED", status(c[0], c[1]), reasons.get(i) + " must release");
            assertEquals(100, stock(c[1]), reasons.get(i) + " must give the stock back");
        }
    }

    @Test
    void orderCancelledReleasesEveryItemOfAMultiItemOrder() throws Exception {
        long a = item(100), b = item(100);
        long order = order();
        reserve(order, a, 2);
        reserve(order, b, 3);

        long offset = send(CANCELLED, order, cancelled(order));
        awaitCommitted(CANCELLED, offset + 1);

        assertEquals(100, stock(a));
        assertEquals(100, stock(b));
    }

    @Test
    void theSameOrderCancelledTwiceAddsStockOnce() throws Exception {
        long item = item(100), order = order();
        reserve(order, item, 4);

        send(CANCELLED, order, cancelled(order));
        long offset = send(CANCELLED, order, cancelled(order));
        awaitCommitted(CANCELLED, offset + 1);

        assertEquals("RELEASED", status(order, item));
        assertEquals(100, stock(item));
        assertEquals(1L, version(item));
    }

    @Test
    void orderCancelledAfterPaymentConfirmedLeavesItConfirmed() throws Exception {
        long item = item(100), order = order();
        reserve(order, item, 3);

        long confirmOffset = send(CONFIRMED, order, confirmed(order));
        awaitCommitted(CONFIRMED, confirmOffset + 1);
        assertEquals("CONFIRMED", status(order, item));

        long cancelOffset = send(CANCELLED, order, cancelled(order));
        awaitCommitted(CANCELLED, cancelOffset + 1);

        assertEquals("CONFIRMED", status(order, item));
        assertEquals(97, stock(item), "paid stock must stay taken");
    }

    // ---- bad payloads ----

    @Test
    void badPayloadsAreSkippedWithAnErrorAndTheNextMessageStillRuns() throws Exception {
        long item = item(100), order = order();
        reserve(order, item, 5);

        send(CANCELLED, "bad", "this is {not json");
        send(CANCELLED, "bad", "{\"reason\":\"PAYMENT_FAILED\"}");
        send(CANCELLED, "bad", "{\"order_id\":\"abc\"}");
        send(CANCELLED, "bad", "{\"order_id\":12.5}");
        long offset = send(CANCELLED, order, cancelled(order));
        awaitCommitted(CANCELLED, offset + 1);

        assertEquals("RELEASED", status(order, item), "the valid message after four bad ones must still be processed");
        assertEquals(100, stock(item));
        List<String> errors = configLogs.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(4, errors.size(), "one ERROR per skipped message: " + errors);
        assertTrue(errors.stream().anyMatch(m -> m.contains("this is {not json")), "the raw message must be in the log");
        assertTrue(errors.stream().anyMatch(m -> m.contains("{\"order_id\":\"abc\"}")));
        assertTrue(configLogs.list.stream().noneMatch(e -> e.getLevel() == Level.WARN),
                "a malformed message must not be retried (no retry WARN)");
    }

    // ---- retry and offsets ----

    // The database fails EVERY add-back while the trigger exists. The release
    // transaction rolls back (status stays RESERVED, stock unchanged), the
    // offset does not move, the message is retried with WARNs. When the
    // database recovers, the same message succeeds and stock rises ONCE.
    @Test
    void aDatabaseErrorIsRetriedAndTheOffsetOnlyMovesAfterTheCommit() throws Exception {
        long item = item(100), order = order();
        reserve(order, item, 6);
        failItemUpdates(item, false);

        long offset = send(CANCELLED, order, cancelled(order));
        await("at least two retry WARNs", () -> configLogs.list.stream().filter(e -> e.getLevel() == Level.WARN).count() >= 2);

        // Still failing: nothing may have changed and the offset must not have moved.
        assertEquals("RESERVED", status(order, item), "the failed transaction must roll back, flip included");
        assertEquals(94, stock(item));
        assertTrue(committed(CANCELLED) <= offset, "the offset moved (" + committed(CANCELLED) + ") before the commit");

        jdbc.update("DROP TRIGGER trg_test_fail_inventory ON flash_sale_items");
        awaitCommitted(CANCELLED, offset + 1);

        assertEquals("RELEASED", status(order, item));
        assertEquals(100, stock(item));
        assertEquals(1L, version(item));
    }

    // Fails the FIRST add-back only (a sequence is not rolled back, so the
    // retry gets a different number). Redelivery after a rolled-back
    // transaction must not add stock twice.
    @Test
    void aMessageFailedOnceIsRedeliveredAndStockIsAddedOnce() throws Exception {
        long item = item(100), order = order();
        reserve(order, item, 2);
        failItemUpdates(item, true);

        long offset = send(CANCELLED, order, cancelled(order));
        awaitCommitted(CANCELLED, offset + 1);

        assertTrue(configLogs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN), "it must have failed and retried once");
        assertEquals("RELEASED", status(order, item));
        assertEquals(100, stock(item), "exactly one add-back");
        assertEquals(1L, version(item));
    }

    // ---- confirm ----

    @Test
    void paymentConfirmedConfirmsAndADuplicateIsQuiet() throws Exception {
        long item = item(100), order = order();
        reserve(order, item, 2);

        send(CONFIRMED, order, confirmed(order));
        long offset = send(CONFIRMED, order, confirmed(order));
        awaitCommitted(CONFIRMED, offset + 1);

        assertEquals("CONFIRMED", status(order, item));
        assertEquals(98, stock(item));
        assertTrue(serviceLogs.list.stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)));
    }

    @Test
    void paymentConfirmedForAReleasedOrderLogsErrorAndChangesNothing() throws Exception {
        long item = item(100), order = order();
        reserve(order, item, 2);
        long cancelOffset = send(CANCELLED, order, cancelled(order));
        awaitCommitted(CANCELLED, cancelOffset + 1);

        long offset = send(CONFIRMED, order, confirmed(order));
        awaitCommitted(CONFIRMED, offset + 1);

        assertEquals("RELEASED", status(order, item));
        assertEquals(100, stock(item));
        assertTrue(serviceLogs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().contains("order " + order)));
    }

    // ---- helpers ----

    private static String cancelled(long order) {
        return "{\"order_id\":" + order + ",\"reason\":\"PAYMENT_FAILED\"}";
    }

    private static String confirmed(long order) {
        return "{\"order_id\":" + order + ",\"buyer_id\":1,\"items\":[],\"amount\":\"499.00\"}";
    }

    private long send(String topic, Object key, String value) throws Exception {
        return kafka.send(topic, key.toString(), value).get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
    }

    /** The committed offset of the listener's group on partition 0 (0 if it never committed). */
    private long committed(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets("inventory-service")
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata o = offsets.get(new TopicPartition(topic, 0));
            return o == null ? 0 : o.offset();
        }
    }

    /** Waits until the group has committed past {@code nextOffset - 1}, i.e. the listener returned for it. */
    private void awaitCommitted(String topic, long nextOffset) {
        await("offset " + nextOffset + " committed on " + topic, () -> {
            try {
                return committed(topic) >= nextOffset;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for " + what);
            }
        }
        throw new AssertionError("timed out after 60 s waiting for " + what + "\nconfig logs: " + configLogs.list);
    }

    /**
     * A BEFORE UPDATE trigger on this one item that raises. {@code firstOnly}:
     * raise only for the first call overall (a sequence survives the rollback).
     */
    private void failItemUpdates(long item, boolean firstOnly) {
        jdbc.update("CREATE SEQUENCE test_fail_inventory_seq");
        jdbc.update("CREATE FUNCTION test_fail_inventory() RETURNS trigger AS $$ BEGIN "
                + (firstOnly ? "IF nextval('test_fail_inventory_seq') = 1 THEN " : "IF true THEN ")
                + "RAISE EXCEPTION 'injected database failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.update("CREATE TRIGGER trg_test_fail_inventory BEFORE UPDATE ON flash_sale_items "
                + "FOR EACH ROW WHEN (NEW.id = " + item + ") EXECUTE FUNCTION test_fail_inventory()");
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
    }

    private long order() {
        return NEXT_ORDER.getAndIncrement();
    }

    private long item(int total) {
        Long productId = jdbc.queryForObject("INSERT INTO products (brand_id, name) VALUES (?, ?) RETURNING id",
                Long.class, BRAND_ID, "Consumer Test Product " + UUID.randomUUID());
        productIds.add(productId);
        Long saleId = jdbc.queryForObject("INSERT INTO flash_sales (brand_id, name, start_time, end_time, status) "
                        + "VALUES (?, ?, now() - interval '1 hour', now() + interval '1 hour', 'ACTIVE') RETURNING id",
                Long.class, BRAND_ID, "Consumer Test Sale " + UUID.randomUUID());
        saleIds.add(saleId);
        Long itemId = jdbc.queryForObject("INSERT INTO flash_sale_items (flash_sale_id, product_id, sale_price, total_stock, available_stock) "
                + "VALUES (?, ?, 3999.00, ?, ?) RETURNING id", Long.class, saleId, productId, total, total);
        itemIds.add(itemId);
        return itemId;
    }

    private void reserve(long order, long item, int qty) {
        jdbc.update("INSERT INTO stock_reservations (order_id, flash_sale_item_id, quantity, status) VALUES (?, ?, ?, 'RESERVED')",
                order, item, qty);
        jdbc.update("UPDATE flash_sale_items SET available_stock = available_stock - ? WHERE id = ?", qty, item);
    }

    private int stock(long item) {
        return jdbc.queryForObject("SELECT available_stock FROM flash_sale_items WHERE id = ?", Integer.class, item);
    }

    private long version(long item) {
        return jdbc.queryForObject("SELECT version FROM flash_sale_items WHERE id = ?", Long.class, item);
    }

    private String status(long order, long item) {
        return jdbc.queryForObject("SELECT status FROM stock_reservations WHERE order_id = ? AND flash_sale_item_id = ?",
                String.class, order, item);
    }
}
