package com.flashmart.order.service;

import com.flashmart.order.TestPayments;
import com.flashmart.order.TestRows;
import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveRequest;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.client.payment.PaymentGatewayClient;
import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.entity.OrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Late reservation, Version A (decisions.md, "Day 7", point 5), through the
 * real create path on real Postgres. Inventory is mocked at {@link InventoryCalls}:
 * while "Inventory" is handling the reserve call, the order is moved to the
 * state under test (what the sweep, a webhook or another loop would do), and
 * then the answer is RESERVED. So {@code priceItem} returns 0 and the loop
 * re-reads a non-CREATED status.
 *
 * <p>What this cannot see: Version B (a timed-out call that commits later;
 * Order never sees it, a documented gap), Inventory actually releasing the
 * stock (tested in inventory-service), the relay and Kafka, and real
 * concurrency (the state change is done synchronously inside the mock).
 *
 * <p>NOT {@code @Transactional}. Buyers 13000..13099 belong to this class.
 */
@SpringBootTest
class LateReservationRepublishTest {

    private static final long FROM = 13000, TO = 13099;
    private static final long SALE = 7L, PRODUCT = 11L;

    @Autowired
    private OrderCreationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private InventoryCalls inventory;

    @MockitoBean
    private PaymentGatewayClient gateway;

    private long nextBuyer = FROM;

    @BeforeEach
    void gatewayAcceptsEverything() {
        TestPayments.acceptEverything(gateway);
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    /** While Inventory "reserves", {@code meanwhile} runs against the order; then the answer is RESERVED. */
    private void reservedAfter(LongConsumer meanwhile) {
        doAnswer(inv -> {
            meanwhile.accept(inv.<ReserveRequest>getArgument(0).orderId());
            return new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal("200.00"), 3L, "Runner");
        }).when(inventory).reserve(any());
    }

    /** Moves the order to {@code status} with what V2/V7 require of it (amount; ref where payable). */
    private void moveTo(long orderId, OrderStatus status) {
        boolean ref = status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.PAID
                || status == OrderStatus.PAYMENT_FAILED;
        jdbc.update("UPDATE orders SET status = ?, amount = 249.00, payment_ref = ? WHERE id = ?",
                status.name(), ref ? "pay_late_" + orderId : null, orderId);
    }

    /** Runs the create; a failure state may answer with an exception, which is not what is tested here. */
    private long create() {
        long buyer = nextBuyer++;
        try {
            service.create(buyer, "k-" + buyer, new CreateOrderRequest(SALE, List.of(new CreateOrderRequest.Item(PRODUCT, 1))));
        } catch (OrderRejectedException expected) {
            // RESERVATION_FAILED and PAYMENT_UNAVAILABLE replay as 409 / 503.
        }
        return jdbc.queryForObject("SELECT id FROM orders WHERE buyer_id = ?", Long.class, buyer);
    }

    private List<String> cancelledPayloads(long orderId) {
        return jdbc.queryForList("SELECT payload_json::text FROM outbox WHERE aggregate_id = ? "
                + "AND event_type = 'OrderCancelled'", String.class, orderId);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class,
            names = {"RESERVATION_FAILED", "PAYMENT_UNAVAILABLE", "ORDER_EXPIRED", "PAYMENT_FAILED"})
    void aReservationForAnOrderInAFailureStateWritesANewOrderCancelled(OrderStatus failure) {
        reservedAfter(id -> moveTo(id, failure));

        long id = create();

        assertEquals(failure.name(), jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id));
        List<String> payloads = cancelledPayloads(id);
        assertEquals(1, payloads.size(), "exactly one new OrderCancelled (the state was set by hand, with none)");
        JsonNode payload = json.readTree(payloads.get(0));
        assertEquals(id, payload.get("order_id").longValue());
        assertEquals(failure.name(), payload.get("reason").stringValue(), "the reason is the order's state");
        assertEquals(jdbc.queryForObject("SELECT buyer_id FROM orders WHERE id = ?", Long.class, id),
                payload.get("buyer_id").longValue());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "PENDING_PAYMENT"})
    void aReservationForAPaidOrPendingOrderWritesNothing(OrderStatus live) {
        reservedAfter(id -> moveTo(id, live));

        long id = create();

        assertEquals(live.name(), jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id));
        assertEquals(List.of(), cancelledPayloads(id), "never OrderCancelled for " + live);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, id));
    }

    // Another loop priced the item first and the order is still CREATED: the
    // loop carries on (resume), pays, and no OrderCancelled is written.
    @Test
    void anItemAlreadyPricedOnACreatedOrderWritesNothingAndTheLoopGoesOn() {
        reservedAfter(id -> jdbc.update("UPDATE order_items SET product_name = 'Runner', unit_price = 200.00, "
                + "subtotal = 200.00 * quantity WHERE order_id = ?", id));

        long id = create();

        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id));
        assertEquals(List.of(), cancelledPayloads(id));
    }
}
