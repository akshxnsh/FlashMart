package com.flashmart.order.controller;

import com.flashmart.order.TestPayments;
import com.flashmart.order.client.inventory.InventoryClient;
import com.flashmart.order.client.payment.PaymentGatewayClient;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The create endpoint through the real Jackson config, validator, exception
 * handler, service and database. Inventory is a mock at the Feign interface.
 *
 * <p>NOT {@code @Transactional}: the request commits for real. Every test uses
 * its own buyer id, and {@link #cleanUp()} deletes this class's rows by buyer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    private static final long BUYER = 5101L; // this class owns buyers 5101..5199

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryClient inventory;

    /** Accepts every initiate; the payment step's own behaviour is tested in OrderPaymentStepTest. */
    @MockitoBean
    private PaymentGatewayClient gateway;

    @org.junit.jupiter.api.BeforeEach
    void gatewayAcceptsEverything() {
        TestPayments.acceptEverything(gateway);
    }

    @AfterEach
    void cleanUp() {
        String mine = "(select id from orders where buyer_id between 5101 and 5199)";
        jdbc.update("delete from outbox where aggregate_id in " + mine);
        jdbc.update("delete from order_items where order_id in " + mine);
        jdbc.update("delete from orders where buyer_id between 5101 and 5199");
    }

    private ResultActions create(String key, Object buyer, String json) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (buyer != null) {
            request.header("X-Buyer-Id", buyer.toString());
        }
        return mockMvc.perform(request);
    }

    private static String body(String items) {
        return "{\"flashSaleId\": 7, \"items\": [" + items + "]}";
    }

    private static String item(long productId, Object quantity) {
        return "{\"productId\": " + productId + ", \"quantity\": " + quantity + "}";
    }

    @Test
    void quantitySixIsA400AndInventoryIsNeverCalled() throws Exception {
        create("k1", BUYER, body(item(11, 6)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        verifyNoInteractions(inventory);
    }

    @Test
    void quantityZeroAndAFractionalQuantityAre400s() throws Exception {
        create("k1", BUYER, body(item(11, 0))).andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
        create("k2", BUYER, body(item(11, 2.5))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        verifyNoInteractions(inventory);
    }

    @Test
    void duplicateProductsAreMergedAndTheCapAppliesToTheTotal() throws Exception {
        create("k1", BUYER, body(item(11, 3) + "," + item(11, 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));

        verifyNoInteractions(inventory);
    }

    @Test
    void moreThanMaxLinesIsA400() throws Exception {
        String items = IntStream.rangeClosed(1, 18).mapToObj(p -> item(p, 1)).collect(Collectors.joining(","));

        create("k1", BUYER, body(items))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LINES"));

        verifyNoInteractions(inventory);
    }

    @Test
    void exactlyMaxLinesIsAccepted() throws Exception {
        given(inventory.reserve(any())).willReturn(new ReserveResponse(
                ReserveOutcome.RESERVED, 1L, new BigDecimal("1.00"), 3L, "Suede"));
        String items = IntStream.rangeClosed(1, 17).mapToObj(p -> item(p, 1)).collect(Collectors.joining(","));

        create("k17", BUYER + 2, body(items)).andExpect(status().isCreated());
    }

    @Test
    void aBlankOrMissingIdempotencyKeyIsA400() throws Exception {
        create("   ", BUYER, body(item(11, 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        create(null, BUYER, body(item(11, 1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        create("x".repeat(256), BUYER, body(item(11, 1))).andExpect(status().isBadRequest());

        verifyNoInteractions(inventory);
    }

    @Test
    void aMissingOrBadBuyerHeaderIsA400() throws Exception {
        create("k1", null, body(item(11, 1))).andExpect(status().isBadRequest());
        create("k1", "abc", body(item(11, 1))).andExpect(status().isBadRequest());
        create("k1", 0, body(item(11, 1))).andExpect(status().isBadRequest());

        verifyNoInteractions(inventory);
    }

    @Test
    void emptyItemsOrAMissingSaleIsA400() throws Exception {
        create("k1", BUYER, body("")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        create("k2", BUYER, "{\"items\": [" + item(11, 1) + "]}").andExpect(status().isBadRequest());

        verifyNoInteractions(inventory);
    }

    @Test
    void aValidOrderIs201PendingPaymentWithRefAmountAsStringAndExpiry() throws Exception {
        given(inventory.reserve(any())).willReturn(new ReserveResponse(
                ReserveOutcome.RESERVED, 1L, new BigDecimal("100.00"), 3L, "Suede"));

        create("k1", BUYER + 1, body(item(11, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.amount").value("149.00")) // a JSON string: 100.00 + 49.00 fee
                .andExpect(jsonPath("$.paymentRef").value(org.hamcrest.Matchers.startsWith("pay_order-")))
                .andExpect(jsonPath("$.expiresAt").isString());
    }
}
