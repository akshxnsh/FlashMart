package com.flashmart.order.service;

import com.flashmart.order.TestPayments;
import com.flashmart.order.TestRows;
import com.flashmart.order.client.payment.PaymentGatewayClient;
import com.flashmart.order.client.inventory.InventoryClient;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unique-violation handling and replays, single-threaded, on real Postgres.
 * NOT {@code @Transactional}: the insert must really roll back and the
 * re-query must really run in a new transaction. Buyers 5200..5299 belong to
 * this class and are deleted after each test.
 */
@SpringBootTest
class OrderCreationServiceTest {

    private static final long FROM = 5200, TO = 5299;
    private static final long SALE = 7L;

    @Autowired
    private OrderCreationService service;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryClient inventory;

    @MockitoSpyBean
    private OrderCreationTransaction transaction;

    /** Accepts every initiate; the payment step's own behaviour is tested in OrderPaymentStepTest. */
    @MockitoBean
    private PaymentGatewayClient gateway;

    @org.junit.jupiter.api.BeforeEach
    void gatewayAcceptsEverything() {
        TestPayments.acceptEverything(gateway);
    }

    private long nextBuyer = FROM;

    @BeforeEach
    void inventoryReservesEverything() {
        given(inventory.reserve(any())).willAnswer(inv -> new ReserveResponse(
                ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product"));
    }

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    private long buyer() {
        return nextBuyer++;
    }

    private static CreateOrderRequest request(long sale, CreateOrderRequest.Item... items) {
        return new CreateOrderRequest(sale, List.of(items));
    }

    private static CreateOrderRequest.Item item(long productId, int quantity) {
        return new CreateOrderRequest.Item(productId, quantity);
    }

    private OrderRejectedException rejected(long buyer, String key, CreateOrderRequest request) {
        return assertThrows(OrderRejectedException.class, () -> service.create(buyer, key, request));
    }

    private void setStatus(long orderId, String status) {
        if (status.equals("PAID") || status.equals("PENDING_PAYMENT")) {
            jdbc.update("update orders set amount = 10.00, payment_ref = 'pay_' || id, status = ? where id = ?",
                    status, orderId);
        } else {
            jdbc.update("update orders set status = ? where id = ?", status, orderId);
        }
    }

    private long countOrders(long buyer) {
        return jdbc.queryForObject("select count(*) from orders where buyer_id = ?", Long.class, buyer);
    }

    // ---- (a) same key ----

    @Test
    void theSameRequestWithTheSameKeyReplaysTheSameOrder() {
        long buyer = buyer();
        CreateOrderRequest request = request(SALE, item(11, 1), item(12, 2));
        CreateOrderResult first = service.create(buyer, "k1", request);

        CreateOrderResult again = service.create(buyer, "k1", request);

        assertTrue(first.created());
        assertFalse(again.created());
        assertEquals(first.body().orderId(), again.body().orderId());
        assertEquals(1, countOrders(buyer));
    }

    @Test
    void itemsInADifferentOrderOrSplitAcrossLinesAreStillAReplay() {
        long buyer = buyer();
        CreateOrderResult first = service.create(buyer, "k1", request(SALE, item(11, 2), item(12, 1)));

        CreateOrderResult again = service.create(buyer, "k1", request(SALE, item(12, 1), item(11, 1), item(11, 1)));

        assertEquals(first.body().orderId(), again.body().orderId());
    }

    @Test
    void theSameKeyForADifferentRequestIsKeyReusedAndCallsNothing() {
        long buyer = buyer();
        service.create(buyer, "k1", request(SALE, item(11, 1), item(12, 2)));
        clearInvocations(inventory);

        List<CreateOrderRequest> others = List.of(
                request(SALE + 1, item(11, 1), item(12, 2)),     // other sale
                request(SALE, item(11, 1), item(13, 2)),         // other product
                request(SALE, item(11, 1), item(12, 3)),         // other quantity
                request(SALE, item(11, 1)));                      // fewer items
        for (CreateOrderRequest other : others) {
            OrderRejectedException e = rejected(buyer, "k1", other);
            assertEquals(HttpStatus.CONFLICT, e.getStatus());
            assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, e.getBody().code());
        }
        verifyNoInteractions(inventory);
        assertEquals(1, countOrders(buyer));
    }

    @Test
    void aReplayOfAFailedOrderIsA409WithNoReasonAndNeverCallsInventory() {
        long buyer = buyer();
        CreateOrderRequest request = request(SALE, item(11, 1));
        long id = service.create(buyer, "k1", request).body().orderId();
        setStatus(id, "RESERVATION_FAILED");
        clearInvocations(inventory);

        OrderRejectedException e = rejected(buyer, "k1", request);

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals(ErrorCode.RESERVATION_FAILED, e.getBody().code());
        assertNull(e.getBody().reason());
        assertEquals(id, e.getBody().orderId());
        verifyNoInteractions(inventory);
    }

    @Test
    void aReplayOfAPaidOrderReturnsItsState() {
        long buyer = buyer();
        CreateOrderRequest request = request(SALE, item(11, 1));
        long id = service.create(buyer, "k1", request).body().orderId();
        setStatus(id, "PAID");

        CreateOrderResult again = service.create(buyer, "k1", request);

        assertFalse(again.created());
        assertEquals("PAID", again.body().status());
        assertEquals(0, new BigDecimal("10.00").compareTo(again.body().amount()));
    }

    // ---- (b) another live-or-paid order for the sale ----

    @Test
    void aSecondKeyForTheSameSaleIsOrderAlreadyOpenWithTheExistingOrder() {
        long buyer = buyer();
        long id = service.create(buyer, "k1", request(SALE, item(11, 1))).body().orderId();

        OrderRejectedException e = rejected(buyer, "k2", request(SALE, item(12, 1)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals(ErrorCode.ORDER_ALREADY_OPEN, e.getBody().code());
        assertEquals(id, e.getBody().orderId());
        assertEquals("PENDING_PAYMENT", e.getBody().status());
        assertEquals(1, countOrders(buyer));
    }

    @Test
    void aPaidOrderForTheSaleSaysAlreadyBought() {
        long buyer = buyer();
        long id = service.create(buyer, "k1", request(SALE, item(11, 1))).body().orderId();
        setStatus(id, "PAID");

        OrderRejectedException e = rejected(buyer, "k2", request(SALE, item(11, 1)));

        assertEquals(ErrorCode.ORDER_ALREADY_OPEN, e.getBody().code());
        assertEquals("PAID", e.getBody().status());
        assertTrue(e.getBody().message().contains("already bought"), e.getBody().message());
    }

    @Test
    void afterAFailedOrderANewKeyCreatesANewOrder() {
        long buyer = buyer();
        long id = service.create(buyer, "k1", request(SALE, item(11, 1))).body().orderId();
        setStatus(id, "RESERVATION_FAILED");

        CreateOrderResult retry = service.create(buyer, "k2", request(SALE, item(11, 1)));

        assertTrue(retry.created());
        assertEquals(2, countOrders(buyer));
    }

    @Test
    void anotherSaleIsNotBlocked() {
        long buyer = buyer();
        service.create(buyer, "k1", request(SALE, item(11, 1)));

        assertTrue(service.create(buyer, "k2", request(SALE + 1, item(11, 1))).created());
    }

    // ---- (c) nothing found ----

    /** The winner vanished between the violation and the re-query (forced by the spy). */
    @Test
    void whenNeitherLookupFindsTheWinnerTheAnswerIsConflictRetry() {
        long buyer = buyer();
        service.create(buyer, "k1", request(SALE, item(11, 1)));
        doReturn(Optional.empty()).when(transaction).findLiveOrPaid(anyLong(), anyLong());

        OrderRejectedException e = rejected(buyer, "k2", request(SALE, item(11, 1)));

        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals(ErrorCode.CONFLICT_RETRY, e.getBody().code());
    }
}
