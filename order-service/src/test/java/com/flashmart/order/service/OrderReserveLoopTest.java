package com.flashmart.order.service;

import com.flashmart.order.TestRows;
import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.InventoryUnavailableException;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveRequest;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The reserve loop on real Postgres, single-threaded. Inventory is mocked at
 * {@link InventoryCalls}, the layer the loop calls, so a timeout is simply an
 * {@link InventoryUnavailableException}; the Feign mapping itself is tested in
 * {@code InventoryClientHttpTest} against a stub.
 *
 * <p>Nothing here asserts that stock is released: that happens in Inventory
 * when it consumes {@code OrderCancelled}, and the relay (Day 6) and consumer
 * (Day 7) are not built. These tests stop at the outbox row.
 *
 * <p>NOT {@code @Transactional}. Buyers 7000..7099 belong to this class.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OrderReserveLoopTest {

    private static final long FROM = 7000, TO = 7099;
    private static final long SALE = 7L;

    @Autowired
    private OrderCreationService service;

    @Autowired
    private OrderCreationTransaction transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryCalls inventory;

    private long nextBuyer = FROM;

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    private static ReserveResponse reserved(String price) {
        return new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal(price), 3L, "Product");
    }

    private static ReserveResponse no(ReserveOutcome outcome) {
        return new ReserveResponse(outcome, null, null, null, null);
    }

    /** Inventory answers per product id. */
    private void inventoryAnswers(Function<Long, ReserveResponse> byProduct) {
        // doAnswer, not given(): re-stubbing with given() would call the previous
        // answer with a null argument while recording the new stub.
        doAnswer(inv -> byProduct.apply(inv.<ReserveRequest>getArgument(0).productId()))
                .when(inventory).reserve(any());
    }

    private static CreateOrderRequest request(long... productIds) {
        return new CreateOrderRequest(SALE, java.util.Arrays.stream(productIds)
                .mapToObj(p -> new CreateOrderRequest.Item(p, 1)).toList());
    }

    private Map<String, Object> order(long id) {
        return jdbc.queryForMap("select status, amount, failure_detail from orders where id = ?", id);
    }

    private List<Map<String, Object>> outbox(long id) {
        return jdbc.queryForList("select event_type, payload_json::text as payload from outbox where aggregate_id = ?",
                id);
    }

    private long pricedItems(long id) {
        return jdbc.queryForObject("select count(*) from order_items where order_id = ? and unit_price is not null",
                Long.class, id);
    }

    private long orderIdOf(long buyer) {
        return jdbc.queryForObject("select id from orders where buyer_id = ?", Long.class, buyer);
    }

    @Test
    void allReservedPricesEveryItemAndSetsTheAmount() {
        long buyer = nextBuyer++;
        inventoryAnswers(p -> reserved(p == 11 ? "100.00" : "25.50"));

        CreateOrderResult result = service.create(buyer, "k1",
                new CreateOrderRequest(SALE, List.of(new CreateOrderRequest.Item(11L, 2),
                        new CreateOrderRequest.Item(12L, 1))));

        assertTrue(result.created());
        assertEquals("CREATED", result.body().status());
        assertEquals(new BigDecimal("274.50"), result.body().amount()); // 2 x 100.00 + 25.50 + 49.00 fee
        long id = result.body().orderId();
        assertEquals(2, pricedItems(id));
        assertEquals(0, new BigDecimal("274.50").compareTo((BigDecimal) order(id).get("amount")));
        assertTrue(outbox(id).isEmpty());
    }

    @Test
    void outOfStockOnTheThirdItemFailsTheOrderWithOneOrderCancelled() {
        long buyer = nextBuyer++;
        inventoryAnswers(p -> p == 13 ? no(ReserveOutcome.OUT_OF_STOCK) : reserved("10.00"));

        OrderRejectedException e = assertThrows(OrderRejectedException.class,
                () -> service.create(buyer, "k1", request(11, 12, 13)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals(ErrorCode.RESERVATION_FAILED, e.getBody().code());
        assertEquals("OUT_OF_STOCK", e.getBody().reason());
        long id = orderIdOf(buyer);
        Map<String, Object> order = order(id);
        assertEquals("RESERVATION_FAILED", order.get("status"));
        assertNull(order.get("amount"));
        assertTrue(((String) order.get("failure_detail")).contains("OUT_OF_STOCK"));
        assertEquals(2, pricedItems(id)); // items 11 and 12 were reserved before 13 failed
        List<Map<String, Object>> events = outbox(id);
        assertEquals(1, events.size());
        assertEquals("OrderCancelled", events.get(0).get("event_type"));
        assertEquals("{\"reason\": \"RESERVATION_FAILED\", \"buyer_id\": " + buyer + ", \"order_id\": " + id + "}",
                events.get(0).get("payload"));
    }

    @Test
    void saleNotLiveAndItemNotInSaleFailTheOrderTheSameWay() {
        for (ReserveOutcome outcome : List.of(ReserveOutcome.SALE_NOT_LIVE, ReserveOutcome.ITEM_NOT_IN_SALE)) {
            long buyer = nextBuyer++;
            inventoryAnswers(p -> no(outcome));

            OrderRejectedException e = assertThrows(OrderRejectedException.class,
                    () -> service.create(buyer, "k1", request(11)));

            assertEquals(outcome.name(), e.getBody().reason());
            assertEquals("RESERVATION_FAILED", order(orderIdOf(buyer)).get("status"));
            assertEquals(1, outbox(orderIdOf(buyer)).size());
        }
    }

    @Test
    void aTimeoutOnTheThirdItemFailsTheOrderWith503AndOneOrderCancelled() {
        long buyer = nextBuyer++;
        given(inventory.reserve(any())).willAnswer(inv -> {
            if (inv.<ReserveRequest>getArgument(0).productId() == 13) {
                throw new InventoryUnavailableException("inventory unreachable: Read timed out", null);
            }
            return reserved("10.00");
        });

        OrderRejectedException e = assertThrows(OrderRejectedException.class,
                () -> service.create(buyer, "k1", request(11, 12, 13)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertEquals(ErrorCode.INVENTORY_UNAVAILABLE, e.getBody().code());
        long id = orderIdOf(buyer);
        assertEquals("RESERVATION_FAILED", order(id).get("status"));
        assertTrue(((String) order(id).get("failure_detail")).contains("Read timed out"));
        assertEquals(1, outbox(id).size());
    }

    @Test
    void releasedWhileOrderSaysCreatedIs500AndChangesNothing(CapturedOutput output) {
        long buyer = nextBuyer++;
        inventoryAnswers(p -> p == 12 ? no(ReserveOutcome.RESERVATION_RELEASED) : reserved("10.00"));

        OrderRejectedException e = assertThrows(OrderRejectedException.class,
                () -> service.create(buyer, "k1", request(11, 12, 13)));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatus());
        assertEquals(ErrorCode.INCONSISTENT_STATE, e.getBody().code());
        long id = orderIdOf(buyer);
        Map<String, Object> order = order(id);
        assertEquals("CREATED", order.get("status")); // no CAS
        assertNull(order.get("failure_detail"));
        assertNull(order.get("amount"));
        assertTrue(outbox(id).isEmpty());              // no event
        assertEquals(1, pricedItems(id));              // stopped at 12; 13 never tried
        verify(inventory, never()).reserve(new ReserveRequest(id, SALE, 13L, 1));
        assertTrue(output.getOut().contains("ERROR") && output.getOut().contains("order " + id + " product 12"),
                "expected an ERROR log naming the order and product");
    }

    @Test
    void releasedAfterAnotherPathFailedTheOrderReportsThatState() {
        long buyer = nextBuyer++;
        given(inventory.reserve(any())).willAnswer(inv -> {
            // Another path (here: native SQL standing in for it) failed the
            // order, and Inventory already released its reservation.
            jdbc.update("update orders set status = 'RESERVATION_FAILED' where buyer_id = ?", buyer);
            return no(ReserveOutcome.RESERVATION_RELEASED);
        });

        OrderRejectedException e = assertThrows(OrderRejectedException.class,
                () -> service.create(buyer, "k1", request(11)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals(ErrorCode.RESERVATION_FAILED, e.getBody().code());
        assertNull(e.getBody().reason());
        assertTrue(outbox(orderIdOf(buyer)).isEmpty()); // this request wrote no event
    }

    @Test
    void aRetryWhileCreatedResumesOnlyTheUnpricedItems() {
        long buyer = nextBuyer++;
        // The first request crashed after pricing item 11.
        long id = transaction.insert(buyer, SALE, "k1",
                List.of(new OrderLine(11L, 1), new OrderLine(12L, 1), new OrderLine(13L, 1)));
        transaction.priceItem(id, 11L, "Product", new BigDecimal("10.00"));
        inventoryAnswers(p -> reserved("10.00"));

        CreateOrderResult retry = service.create(buyer, "k1", request(11, 12, 13));

        assertFalse(retry.created());
        assertEquals(id, retry.body().orderId());
        assertEquals(new BigDecimal("79.00"), retry.body().amount());
        verify(inventory, never()).reserve(new ReserveRequest(id, SALE, 11L, 1));
        verify(inventory).reserve(new ReserveRequest(id, SALE, 12L, 1));
        verify(inventory).reserve(new ReserveRequest(id, SALE, 13L, 1));
    }

    @Test
    void aRetryAfterTheDeadlineDoesNotResumeAndNeverCallsInventory() {
        long buyer = nextBuyer++;
        long id = transaction.insert(buyer, SALE, "k1", List.of(new OrderLine(11L, 1)));
        jdbc.update("update orders set expires_at = clock_timestamp() - interval '1 second' where id = ?", id);

        CreateOrderResult retry = service.create(buyer, "k1", request(11));

        assertFalse(retry.created());
        assertEquals("CREATED", retry.body().status());
        assertNull(retry.body().amount());
        verifyNoInteractions(inventory);
    }

    @Test
    void aRetryOfAFullyPricedCreatedOrderReturnsItWithoutCallingInventory() {
        long buyer = nextBuyer++;
        inventoryAnswers(p -> reserved("10.00"));
        long id = service.create(buyer, "k1", request(11)).body().orderId();
        org.mockito.Mockito.clearInvocations(inventory);

        CreateOrderResult retry = service.create(buyer, "k1", request(11));

        assertEquals(id, retry.body().orderId());
        assertNotNull(retry.body().amount());
        verifyNoInteractions(inventory);
    }
}
