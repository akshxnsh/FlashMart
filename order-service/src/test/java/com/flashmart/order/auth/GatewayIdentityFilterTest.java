package com.flashmart.order.auth;

import com.flashmart.order.TestGatewayHeaders;
import com.flashmart.order.TestPayments;
import com.flashmart.order.client.inventory.InventoryClient;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.client.payment.PaymentGatewayClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Order's check of the Gateway-signed buyer id, through the real filter,
 * controller, service and database (Inventory and the payment gateway mocked).
 * NOT {@code @Transactional}: requests commit; this class owns buyers
 * 13500..13599 and deletes their rows afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayIdentityFilterTest {

    private static final long BUYER = 13500L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** The effective value: an exported PAYMENT_WEBHOOK_SECRET outranks the test config. */
    @Value("${payment-webhook.secret}")
    private String webhookSecret;

    @MockitoBean
    private InventoryClient inventory;

    @MockitoBean
    private PaymentGatewayClient gateway;

    @BeforeEach
    void stubs() {
        TestPayments.acceptEverything(gateway);
        given(inventory.reserve(any())).willReturn(new ReserveResponse(
                ReserveOutcome.RESERVED, 1L, new BigDecimal("100.00"), 3L, "Suede"));
    }

    @AfterEach
    void cleanUp() {
        String mine = "(select id from orders where buyer_id between 13500 and 13599)";
        jdbc.update("delete from outbox where aggregate_id in " + mine);
        jdbc.update("delete from order_items where order_id in " + mine);
        jdbc.update("delete from orders where buyer_id between 13500 and 13599");
    }

    private static MockHttpServletRequestBuilder order(String key) {
        return post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"flashSaleId\": 7, \"items\": [{\"productId\": 11, \"quantity\": 1}]}");
    }

    private ResultActions expect401(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isString());
    }

    private long ordersOf(long buyer) {
        return jdbc.queryForObject("select count(*) from orders where buyer_id = ?", Long.class, buyer);
    }

    @Test
    void aValidSignatureCreatesTheOrderForTheSignedBuyer() throws Exception {
        mockMvc.perform(TestGatewayHeaders.signedAs(order("k1"), BUYER))
                .andExpect(status().isCreated());

        assertThat(ordersOf(BUYER)).isEqualTo(1);
    }

    @Test
    void missingIdentityHeadersAre401() throws Exception {
        long now = Instant.now().getEpochSecond();
        String ts = Long.toString(now);
        String sig = TestGatewayHeaders.signature(Long.toString(BUYER), ts);

        // A bare X-Buyer-Id, as every caller could send before Day 8.
        expect401(order("k1").header("X-Buyer-Id", BUYER));
        expect401(order("k1").header("X-Auth-Timestamp", ts).header("X-Auth-Signature", sig));
        expect401(order("k1").header("X-Buyer-Id", BUYER).header("X-Auth-Signature", sig));
        expect401(order("k1").header("X-Buyer-Id", BUYER).header("X-Auth-Timestamp", ts));
        expect401(order("k1"));

        verifyNoInteractions(inventory);
        assertThat(ordersOf(BUYER)).isZero();
    }

    @Test
    void aBadSignatureIs401() throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        String wrongKey = TestGatewayHeaders.hmacHex("a-different-secret-of-at-least-32-bytes!!", BUYER + "." + ts);
        expect401(order("k1").header("X-Buyer-Id", BUYER).header("X-Auth-Timestamp", ts)
                .header("X-Auth-Signature", wrongKey));
        expect401(order("k1").header("X-Buyer-Id", BUYER).header("X-Auth-Timestamp", ts)
                .header("X-Auth-Signature", "not-hex"));

        verifyNoInteractions(inventory);
        assertThat(ordersOf(BUYER)).isZero();
    }

    @Test
    void aSignatureForBuyer1SentWithBuyer2Is401() throws Exception {
        long buyer1 = BUYER + 1, buyer2 = BUYER + 2;
        String ts = Long.toString(Instant.now().getEpochSecond());
        String sigFor1 = TestGatewayHeaders.signature(Long.toString(buyer1), ts);

        expect401(order("k1").header("X-Buyer-Id", buyer2).header("X-Auth-Timestamp", ts)
                .header("X-Auth-Signature", sigFor1));

        verifyNoInteractions(inventory);
        assertThat(ordersOf(buyer2)).isZero();
    }

    @Test
    void aTimestampChangedAfterSigningIs401() throws Exception {
        long now = Instant.now().getEpochSecond();
        String sigForOld = TestGatewayHeaders.signature(Long.toString(BUYER), Long.toString(now - 120));
        // A captured stale request with its timestamp bumped to "now": the signature no longer matches.
        expect401(order("k1").header("X-Buyer-Id", BUYER).header("X-Auth-Timestamp", now)
                .header("X-Auth-Signature", sigForOld));
    }

    @Test
    void aStaleSignatureIs401() throws Exception {
        long now = Instant.now().getEpochSecond();
        expect401(TestGatewayHeaders.signedAs(order("k1"), Long.toString(BUYER), now - 61))
                .andExpect(jsonPath("$.message").value("gateway signature is too old"));
        verifyNoInteractions(inventory);
    }

    @Test
    void aFutureTimestampIs401() throws Exception {
        long now = Instant.now().getEpochSecond();
        expect401(TestGatewayHeaders.signedAs(order("k1"), Long.toString(BUYER), now + 6))
                .andExpect(jsonPath("$.message").value("gateway timestamp is in the future"));
        verifyNoInteractions(inventory);
    }

    @Test
    void justInsideBothWindowsIsAccepted() throws Exception {
        long now = Instant.now().getEpochSecond();
        mockMvc.perform(TestGatewayHeaders.signedAs(order("k-old"), Long.toString(BUYER + 3), now - 55))
                .andExpect(status().isCreated());
        mockMvc.perform(TestGatewayHeaders.signedAs(order("k-new"), Long.toString(BUYER + 4), now + 3))
                .andExpect(status().isCreated());
    }

    @Test
    void theWebhookIsNotBehindTheIdentityCheck() throws Exception {
        String body = "{\"order_id\": 987654321, \"payment_ref\": \"pay_x\", \"status\": \"SUCCESS\", \"amount\": \"1.00\"}";
        String sig = TestGatewayHeaders.hmacHex(webhookSecret, body);
        // No identity headers at all: its own HMAC decides, and the order is unknown.
        mockMvc.perform(post("/webhooks/payment").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sig).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("ORDER_NOT_FOUND"));
    }
}
