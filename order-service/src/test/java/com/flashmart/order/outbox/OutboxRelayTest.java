package com.flashmart.order.outbox;

import com.flashmart.order.TestRows;
import com.flashmart.order.entity.OrderStatus;
import com.flashmart.order.webhook.PaymentWebhook;
import com.flashmart.order.webhook.PaymentWebhookService;
import com.flashmart.order.webhook.WebhookOutcome;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbox relay against real Postgres and an in-JVM Kafka broker
 * ({@code @EmbeddedKafka}, KRaft). NOT a separate broker process, NOT the
 * docker-compose Kafka, and no process is really killed: a crash is simulated
 * by failing the {@code published_at} UPDATE so the transaction rolls back.
 *
 * <p>The relay schedule is off in tests; every pass here is an explicit
 * {@link OutboxRelay#runOnce()}. Topic creation is on, so the two topics are
 * created by Order's own {@code NewTopic} beans, as in production.
 *
 * <p>The relay claims EVERY unpublished row in the shared dev database, so
 * {@link #onlyOurRows()} refuses to run on a database holding someone else's.
 * Outbox rows here use fake order ids {@value #FROM_ID}+ (outbox has no FK);
 * the end-to-end test uses real orders of buyers 11000..11099. NOT
 * {@code @Transactional}: everything commits and is deleted afterwards.
 */
@SpringBootTest(properties = {"kafka.bootstrap=${spring.embedded.kafka.brokers}", "kafka.create-topics=true"})
@EmbeddedKafka(partitions = 1)
class OutboxRelayTest {

    private static final long FROM_ID = 990_000_000L, TO_ID = 990_999_999L;
    private static final long FROM_BUYER = 11000, TO_BUYER = 11099;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ProducerFactory<String, String> producerFactory;

    @Autowired
    private PaymentWebhookService webhooks;

    @Autowired
    private ObjectMapper json;

    @Value("${spring.embedded.kafka.brokers}")
    private String brokers;

    /** Static: topics outlive a test, so a key must never be reused by a later test in this class. */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong(FROM_ID);
    /** The keys THIS test wrote; {@link #ours} reads back only these. */
    private final Set<String> mine = new java.util.HashSet<>();
    private final List<DefaultKafkaProducerFactory<?, ?>> extraFactories = new ArrayList<>();

    @BeforeEach
    void onlyOurRows() {
        Long foreign = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL "
                + "AND aggregate_id NOT BETWEEN ? AND ?", Long.class, FROM_ID, TO_ID);
        assertEquals(0L, foreign, "the dev database holds unpublished outbox rows this test did not write; "
                + "the relay would publish them. Reset it (scripts/seed) before running this class.");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DROP TRIGGER IF EXISTS trg_test_fail_publish ON outbox");
        jdbc.update("DROP FUNCTION IF EXISTS test_fail_publish()");
        jdbc.update("DELETE FROM outbox WHERE aggregate_id BETWEEN ? AND ?", FROM_ID, TO_ID);
        TestRows.deleteOrdersOfBuyers(jdbc, FROM_BUYER, TO_BUYER);
        extraFactories.forEach(DefaultKafkaProducerFactory::destroy);
    }

    // ---- helpers ----

    /** An unpublished row with a fake order id; returns that id (also the Kafka key). */
    private long row(String eventType) {
        long orderId = NEXT_ID.getAndIncrement();
        mine.add(Long.toString(orderId));
        String payload = eventType.equals("PaymentConfirmed")
                ? "{\"order_id\":" + orderId + ",\"buyer_id\":1,\"items\":[],\"amount\":\"499.00\"}"
                : "{\"order_id\":" + orderId + ",\"reason\":\"PAYMENT_FAILED\",\"buyer_id\":1}";
        jdbc.update("INSERT INTO outbox (aggregate_id, event_type, payload_json) VALUES (?, ?, ?::jsonb)",
                orderId, eventType, payload);
        return orderId;
    }

    private Map<String, Object> state(long orderId) {
        return jdbc.queryForMap("SELECT published_at, attempts, last_error, payload_json::text AS payload "
                + "FROM outbox WHERE aggregate_id = ?", orderId);
    }

    private OutboxRelay relayWith(KafkaTemplate<String, String> kafka, int batchSize, long ackTimeoutMs) {
        return new OutboxRelay(jdbc, tx, kafka, new RelaySettings(batchSize, ackTimeoutMs));
    }

    /** Every record on {@code topic} whose key is one of ours, in offset order. Fresh group, from the start. */
    private List<ConsumerRecord<String, String>> ours(String topic) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long quietUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < quietUntil) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                    records.add(r);
                    quietUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                }
            }
        }
        return records.stream().filter(r -> mine.contains(r.key())).toList();
    }

    private long outboxIdOf(long orderId) {
        return jdbc.queryForObject("SELECT id FROM outbox WHERE aggregate_id = ?", Long.class, orderId);
    }

    private static List<String> keys(List<ConsumerRecord<String, String>> records) {
        return records.stream().map(ConsumerRecord::key).toList();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---- tests ----

    @Test
    void topicsAreCreatedByOrdersOwnBeansWithOnePartition() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers))) {
            Map<String, TopicDescription> topics = admin.describeTopics(
                    List.of(OutboxTopics.PAYMENT_CONFIRMED, OutboxTopics.ORDER_CANCELLED)).allTopicNames().get();
            topics.values().forEach(t -> assertEquals(1, t.partitions().size(), t.name()));
        }
    }

    @Test
    void publishesEachRowToItsTopicKeyedByOrderIdWithTheStoredPayloadThenMarksIt() {
        long paid = row("PaymentConfirmed");
        long cancelled = row("OrderCancelled");

        OutboxRelay.Result result = relay.runOnce();

        assertEquals(new OutboxRelay.Result(2, 2, null), result);
        assertNotNull(state(paid).get("published_at"));
        assertNotNull(state(cancelled).get("published_at"));
        List<ConsumerRecord<String, String>> confirmed = ours(OutboxTopics.PAYMENT_CONFIRMED);
        List<ConsumerRecord<String, String>> cancels = ours(OutboxTopics.ORDER_CANCELLED);
        assertEquals(List.of(Long.toString(paid)), keys(confirmed));
        assertEquals(List.of(Long.toString(cancelled)), keys(cancels));
        assertEquals(state(paid).get("payload"), confirmed.getFirst().value(), "value is payload_json as stored");
        assertEquals(state(cancelled).get("payload"), cancels.getFirst().value());

        assertEquals(new OutboxRelay.Result(0, 0, null), relay.runOnce(), "a second pass claims nothing");
        assertEquals(1, ours(OutboxTopics.PAYMENT_CONFIRMED).size());
        assertEquals(1, ours(OutboxTopics.ORDER_CANCELLED).size());
    }

    /**
     * Broker unreachable: the first row fails within the ack timeout, nothing is
     * marked, the batch stops there. Then a working relay sends all three, in order.
     */
    @Test
    void anUnreachableBrokerMarksNothingStopsAtTheFirstRowThenTheRowsGoOutInOrder() throws Exception {
        long first = row("PaymentConfirmed");
        long second = row("PaymentConfirmed");
        long third = row("PaymentConfirmed");
        long ackTimeoutMs = 1000;
        var deadFactory = new DefaultKafkaProducerFactory<String, String>(
                OutboxKafkaConfig.producerProperties("127.0.0.1:" + freePort(), ackTimeoutMs));
        extraFactories.add(deadFactory);

        long started = System.nanoTime();
        OutboxRelay.Result result = relayWith(new KafkaTemplate<>(deadFactory), 50, ackTimeoutMs).runOnce();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(new OutboxRelay.Result(3, 0, outboxIdOf(first)), result);
        assertTrue(tookMs < ackTimeoutMs + 1500, "one failure should cost about one ack timeout, took " + tookMs);
        for (long id : List.of(first, second, third)) {
            assertNull(state(id).get("published_at"), "nothing is marked published without an ack");
        }
        assertEquals(1, state(first).get("attempts"));
        assertNotNull(state(first).get("last_error"));
        assertEquals(0, state(second).get("attempts"), "rows after the failure are untouched");
        assertNull(state(second).get("last_error"));
        assertEquals(0, state(third).get("attempts"));
        assertTrue(ours(OutboxTopics.PAYMENT_CONFIRMED).isEmpty());

        assertEquals(new OutboxRelay.Result(3, 3, null), relay.runOnce());
        assertEquals(List.of(Long.toString(first), Long.toString(second), Long.toString(third)),
                keys(ours(OutboxTopics.PAYMENT_CONFIRMED)), "sent in created_at order");
        assertEquals(1, state(first).get("attempts"), "the failed attempt stays recorded");
    }

    /**
     * The ack arrives but the published_at UPDATE fails (a stand-in for a crash
     * between ack and commit): the pass rolls back, the row is sent again on the
     * next pass. Two messages, zero lost.
     */
    @Test
    void aFailureAfterTheAckGivesADuplicateNotALoss() {
        long id = row("PaymentConfirmed");
        jdbc.execute("CREATE FUNCTION test_fail_publish() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'simulated crash after the ack'; END $$");
        jdbc.execute("CREATE TRIGGER trg_test_fail_publish BEFORE UPDATE OF published_at ON outbox "
                + "FOR EACH ROW WHEN (OLD.aggregate_id = " + id + ") EXECUTE FUNCTION test_fail_publish()");

        assertThrows(RuntimeException.class, relay::runOnce, "the pass rolls back");
        assertNull(state(id).get("published_at"));
        assertEquals(1, ours(OutboxTopics.PAYMENT_CONFIRMED).size(), "it was sent before the failure");

        jdbc.execute("DROP TRIGGER trg_test_fail_publish ON outbox");
        assertEquals(new OutboxRelay.Result(1, 1, null), relay.runOnce());

        assertEquals(List.of(Long.toString(id), Long.toString(id)), keys(ours(OutboxTopics.PAYMENT_CONFIRMED)),
                "at-least-once: a duplicate, not a loss");
        assertNotNull(state(id).get("published_at"));
    }

    /**
     * Instance A claims rows 1-3 and is held inside its first send, its row
     * locks open. Instance B must not wait for them: it skips them, takes rows
     * 4-6 and finishes while A is still holding. Each row reaches Kafka once.
     */
    @Test
    void twoRelayInstancesNeverClaimTheSameRow() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(row("OrderCancelled"));
        }
        CountDownLatch aInSend = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        KafkaTemplate<String, String> held = new KafkaTemplate<>(producerFactory) {
            @Override
            public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
                aInSend.countDown();
                try {
                    releaseA.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.send(topic, key, data);
            }
        };
        KafkaTemplate<String, String> plain = new KafkaTemplate<>(producerFactory);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<OutboxRelay.Result> a = pool.submit(() -> relayWith(held, 3, 5000).runOnce());
            assertTrue(aInSend.await(10, TimeUnit.SECONDS), "A never reached its first send");
            Future<OutboxRelay.Result> b = pool.submit(() -> relayWith(plain, 3, 5000).runOnce());

            OutboxRelay.Result bResult = b.get(10, TimeUnit.SECONDS); // times out if B waits on A's locks
            assertEquals(new OutboxRelay.Result(3, 3, null), bResult);
            assertFalse(a.isDone(), "A is still holding rows 1-3 while B finished");
            assertEquals(ids.subList(3, 6).stream().map(String::valueOf).toList(),
                    keys(ours(OutboxTopics.ORDER_CANCELLED)), "B took exactly the rows A did not hold");

            releaseA.countDown();
            assertEquals(new OutboxRelay.Result(3, 3, null), a.get(10, TimeUnit.SECONDS));
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
        List<String> all = keys(ours(OutboxTopics.ORDER_CANCELLED));
        assertEquals(6, all.size(), "each row exactly once: " + all);
        assertEquals(ids.stream().map(String::valueOf).collect(Collectors.toSet()), Set.copyOf(all));
    }

    /** Webhook -> outbox -> relay -> Kafka, read back by a real consumer. */
    @Test
    void endToEndAPaidWebhookArrivesOnPaymentConfirmed() {
        long buyer = FROM_BUYER;
        Long orderId = jdbc.queryForObject("INSERT INTO orders (buyer_id, flash_sale_id, status, idempotency_key, "
                + "expires_at, amount, payment_ref) VALUES (?, 7, 'PENDING_PAYMENT', 'k', "
                + "clock_timestamp() + interval '1 hour', 499.00, 'pay_e2e') RETURNING id", Long.class, buyer);
        jdbc.update("INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price, subtotal) "
                + "VALUES (?, 11, 'Runner', 1, 200.00, 200.00), (?, 12, 'Sock', 2, 125.00, 250.00)", orderId, orderId);
        mine.add(Long.toString(orderId));

        assertEquals(WebhookOutcome.PAID, webhooks.apply(
                new PaymentWebhook(orderId, "pay_e2e", PaymentWebhook.Status.SUCCESS, new BigDecimal("499.00"))));
        assertEquals(new OutboxRelay.Result(1, 1, null), relay.runOnce());

        List<ConsumerRecord<String, String>> records = ours(OutboxTopics.PAYMENT_CONFIRMED);
        assertEquals(1, records.size());
        JsonNode payload = json.readTree(records.getFirst().value());
        assertEquals(orderId, payload.get("order_id").longValue());
        assertEquals(buyer, payload.get("buyer_id").longValue());
        assertTrue(payload.get("amount").isString(), "money is a JSON string");
        assertEquals("499.00", payload.get("amount").stringValue());
        assertFalse(payload.has("brand_id"));
        assertFalse(payload.has("event_id"), "flat and bare: no envelope");
        JsonNode runner = payload.get("items").get(0);
        assertEquals("200.00", runner.get("unit_price").stringValue());
        assertEquals("200.00", runner.get("subtotal").stringValue());
        assertEquals("Runner", runner.get("product_name").stringValue());
    }
}
