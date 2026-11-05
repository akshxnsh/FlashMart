package com.flashmart.order.webhook;

import com.flashmart.order.entity.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static com.flashmart.order.webhook.WebhookRows.AMOUNT;
import static com.flashmart.order.webhook.WebhookRows.body;
import static com.flashmart.order.webhook.WebhookRows.refOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /webhooks/payment} through the real controller, signature check,
 * parser, service, CAS and database (decisions.md, "Day 6").
 *
 * <p>{@code @Transactional}: MockMvc runs on the test thread, so the service's
 * transaction joins the test's and everything is rolled back. The flip side:
 * "200 only after commit" is NOT exercised here (nothing commits);
 * {@link PaymentWebhookRaceTest} commits for real. Buyers 10000..10099.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentWebhookControllerTest {

    private static final String URL = "/webhooks/payment";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WebhookSignature signature;

    @Autowired
    private ObjectMapper json;

    private WebhookRows rows;
    private long nextBuyer = 10000;

    @BeforeEach
    void setUp() {
        rows = new WebhookRows(jdbc);
    }

    private long buyer() {
        return nextBuyer++;
    }

    private ResultActions send(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return send(bytes, HexFormat.of().formatHex(signature.sign(bytes)));
    }

    private ResultActions send(byte[] bytes, String signatureHex) throws Exception {
        var request = post(URL).contentType(MediaType.APPLICATION_JSON).content(bytes);
        if (signatureHex != null) {
            request = request.header(PaymentWebhookController.SIGNATURE_HEADER, signatureHex);
        }
        return mockMvc.perform(request);
    }

    private void assertUntouched(long id, OrderStatus status) {
        assertEquals(status.name(), rows.status(id));
        assertEquals(0, rows.outboxAll(id), "outbox rows");
        assertEquals(0, rows.refunds(id), "refund rows");
    }

    // ---- happy path ----

    @Test
    void successForTheHeldRefAndAmountPaysTheOrderAndWritesOnePaymentConfirmed() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);

        send(body(id, refOf(buyer), "SUCCESS", "499.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PAID"));

        assertEquals("PAID", rows.status(id));
        assertEquals(1, rows.outbox(id, "PaymentConfirmed"));
        assertEquals(1, rows.outboxAll(id));
        assertEquals(0, rows.refunds(id));
        JsonNode payload = json.readTree(rows.payload(id, "PaymentConfirmed"));
        assertEquals(id, payload.get("order_id").longValue());
        assertEquals(buyer, payload.get("buyer_id").longValue());
        assertEquals("499.00", payload.get("amount").stringValue(), "money is a JSON string");
        assertFalse(payload.has("brand_id"), "no brand_id (Day 6, point 5)");
        assertEquals(2, payload.get("items").size());
        JsonNode sock = payload.get("items").get(1);
        assertEquals(12L, sock.get("product_id").longValue());
        assertEquals("Sock", sock.get("product_name").stringValue());
        assertEquals(2, sock.get("quantity").intValue());
        assertEquals("125.00", sock.get("unit_price").stringValue());
        assertEquals("250.00", sock.get("subtotal").stringValue());
    }

    // ---- signature: 401, nothing written ----

    @Test
    void missingSignatureIs401AndWritesNothing() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);

        send(body(id, refOf(buyer), "SUCCESS", "499.00").getBytes(StandardCharsets.UTF_8), null)
                .andExpect(status().isUnauthorized());

        assertUntouched(id, OrderStatus.PENDING_PAYMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-hex", "00",
            "0000000000000000000000000000000000000000000000000000000000000000"})
    void wrongSignatureIs401AndWritesNothing(String signatureHex) throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);

        send(body(id, refOf(buyer), "SUCCESS", "499.00").getBytes(StandardCharsets.UTF_8), signatureHex)
                .andExpect(status().isUnauthorized());

        assertUntouched(id, OrderStatus.PENDING_PAYMENT);
    }

    /** Signed for one body, sent with another: the HMAC is over the bytes, so any change breaks it. */
    @Test
    void tamperedBodyIs401AndWritesNothing() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);
        byte[] signed = body(id, refOf(buyer), "FAILED", "499.00").getBytes(StandardCharsets.UTF_8);
        byte[] sent = body(id, refOf(buyer), "SUCCESS", "499.00").getBytes(StandardCharsets.UTF_8);

        send(sent, HexFormat.of().formatHex(signature.sign(signed))).andExpect(status().isUnauthorized());

        assertUntouched(id, OrderStatus.PENDING_PAYMENT);
    }

    /** The signature is checked before parsing: a body that is not even JSON is 401, not 400, when unsigned. */
    @Test
    void unsignedGarbageIs401NotA400() throws Exception {
        send("not json".getBytes(StandardCharsets.UTF_8), "ab").andExpect(status().isUnauthorized());
    }

    @Test
    void uppercaseHexSignatureIsAccepted() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);
        byte[] bytes = body(id, refOf(buyer), "SUCCESS", "499.00").getBytes(StandardCharsets.UTF_8);

        send(bytes, HexFormat.of().withUpperCase().formatHex(signature.sign(bytes))).andExpect(status().isOk());

        assertEquals("PAID", rows.status(id));
    }

    @Test
    void aBlankSecretFailsStartup() {
        assertThrows(IllegalStateException.class, () -> new WebhookSignature(" "));
        assertThrows(IllegalStateException.class, () -> new WebhookSignature(""));
    }

    // ---- 400 / 404 ----

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[]",
            "{\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"1.00\"}",
            "{\"order_id\":\"1\",\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"1.00\"}",
            "{\"order_id\":1.5,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"1.00\"}",
            "{\"order_id\":0,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"1.00\"}",
            "{\"order_id\":1,\"payment_ref\":\" \",\"status\":\"SUCCESS\",\"amount\":\"1.00\"}",
            "{\"order_id\":1,\"payment_ref\":\"p\",\"status\":\"PENDING\",\"amount\":\"1.00\"}",
            "{\"order_id\":1,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":1.00}",
            "{\"order_id\":1,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"abc\"}",
            "{\"order_id\":1,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"0\"}",
            "{\"order_id\":1,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"-1.00\"}",
            "{\"order_id\":1,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"1.001\"}",
            "{\"order_id\":1,\"payment_ref\":\"p\",\"status\":\"SUCCESS\",\"amount\":\"1000000000000.00\"}"
    })
    void malformedButSignedBodyIs400(String body) throws Exception {
        send(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.outcome").value("INVALID_REQUEST"));
    }

    @Test
    void aRefLongerThanTheColumnIs400() throws Exception {
        send(body(1, "p".repeat(256), "SUCCESS", "1.00")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownOrderIs404() throws Exception {
        long missing = Long.MAX_VALUE - 1; // BIGSERIAL never gets here

        send(body(missing, "pay_x", "SUCCESS", "499.00")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.outcome").value("ORDER_NOT_FOUND"));

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refunds WHERE order_id = ?", Long.class, missing));
    }

    // ---- B4: early webhook, 409, nothing changed ----

    @Test
    void earlySuccessOnACreatedOrderIs409AndChangesNothing() throws Exception {
        long id = rows.order(buyer(), OrderStatus.CREATED);

        send(body(id, "pay_early", "SUCCESS", "499.00")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("TOO_EARLY"));

        assertUntouched(id, OrderStatus.CREATED);
        assertEquals(null, rows.paymentRef(id));
    }

    @Test
    void earlyFailedOnACreatedOrderIs409AndChangesNothing() throws Exception {
        long id = rows.order(buyer(), OrderStatus.CREATED);

        send(body(id, "pay_early", "FAILED", "499.00")).andExpect(status().isConflict());

        assertUntouched(id, OrderStatus.CREATED);
    }

    // ---- redelivery and the one-payment invariant ----

    @Test
    void redeliveredSuccessIsANoOp() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);
        String body = body(id, refOf(buyer), "SUCCESS", "499.00");

        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("PAID"));
        send(body).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ALREADY_PAID"));

        assertEquals("PAID", rows.status(id));
        assertEquals(1, rows.outbox(id, "PaymentConfirmed"));
        assertEquals(0, rows.refunds(id));
    }

    /** A second captured payment Y on a PAID order is refunded once, however often its webhook arrives. */
    @Test
    void aDifferentRefOnAPaidOrderIsRefundedExactlyOnce() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PAID);
        String second = body(id, "pay_second", "SUCCESS", "499.00");

        send(second).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("REFUND_RECORDED"));
        send(second).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("REFUND_RECORDED"));

        assertEquals("PAID", rows.status(id));
        assertEquals(refOf(buyer), rows.paymentRef(id), "the order keeps its own payment");
        assertEquals(1, rows.refunds(id));
        assertEquals("pay_second", jdbc.queryForObject(
                "SELECT payment_ref FROM refunds WHERE order_id = ?", String.class, id));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM refunds WHERE order_id = ?", String.class, id));
        assertEquals(0, rows.outboxAll(id));
    }

    /** Day 6, point 3: right ref, wrong amount loses the CAS; the refund is for what was captured. */
    @Test
    void wrongAmountLeavesTheOrderPendingAndRefundsTheWebhookAmount() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);

        send(body(id, refOf(buyer), "SUCCESS", "498.99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUND_RECORDED"));

        assertEquals("PENDING_PAYMENT", rows.status(id));
        assertEquals(0, rows.outboxAll(id));
        assertEquals(0, new BigDecimal("498.99").compareTo(jdbc.queryForObject(
                "SELECT amount FROM refunds WHERE order_id = ?", BigDecimal.class, id)));
        assertEquals(refOf(buyer), jdbc.queryForObject(
                "SELECT payment_ref FROM refunds WHERE order_id = ?", String.class, id));
    }

    @Test
    void amount499EqualsTheStored499_00() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);
        assertEquals(0, AMOUNT.compareTo(new BigDecimal("499")));

        send(body(id, refOf(buyer), "SUCCESS", "499")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PAID"));

        assertEquals("PAID", rows.status(id));
        assertEquals(0, rows.refunds(id));
    }

    /** A different ref on a PENDING_PAYMENT order: refunded, and the order keeps waiting for its own payment. */
    @Test
    void aDifferentRefOnAPendingOrderIsRefundedAndTheOrderKeepsWaiting() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);

        send(body(id, "pay_other", "SUCCESS", "499.00")).andExpect(status().isOk());

        assertEquals("PENDING_PAYMENT", rows.status(id));
        assertEquals(1, rows.refunds(id));
    }

    // ---- FAILED ----

    @Test
    void failedForAnotherRefLeavesTheOrderUntouched() throws Exception {
        long id = rows.order(buyer(), OrderStatus.PENDING_PAYMENT);

        send(body(id, "pay_other", "FAILED", "499.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED"));

        assertUntouched(id, OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void failedForTheHeldRefFailsTheOrderAndWritesOrderCancelled() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PENDING_PAYMENT);

        send(body(id, refOf(buyer), "FAILED", "499.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PAYMENT_FAILED"));

        assertEquals("PAYMENT_FAILED", rows.status(id));
        assertEquals(1, rows.outbox(id, "OrderCancelled"));
        assertEquals(1, rows.outboxAll(id));
        assertEquals(0, rows.refunds(id));
        JsonNode payload = json.readTree(rows.payload(id, "OrderCancelled"));
        assertEquals(id, payload.get("order_id").longValue());
        assertEquals("PAYMENT_FAILED", payload.get("reason").stringValue());
        assertEquals(buyer, payload.get("buyer_id").longValue());
    }

    /** A late FAILED for the payment a PAID order holds changes nothing, and must never cancel it. */
    @Test
    void failedOnAPaidOrderIsIgnored() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.PAID);

        send(body(id, refOf(buyer), "FAILED", "499.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED"));

        assertUntouched(id, OrderStatus.PAID);
    }

    // ---- terminal orders: a SUCCESS is a payment nobody keeps ----

    @Test
    void successOnAnExpiredOrderIsRefunded() throws Exception {
        long buyer = buyer();
        long id = rows.order(buyer, OrderStatus.ORDER_EXPIRED);

        send(body(id, refOf(buyer), "SUCCESS", "499.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUND_RECORDED"));

        assertEquals("ORDER_EXPIRED", rows.status(id));
        assertEquals(1, rows.refunds(id));
        assertEquals(0, rows.outboxAll(id));
    }

    /** initiate timed out but the payment existed: the order holds NO ref, so the refund's ref comes from the webhook. */
    @Test
    void successOnAPaymentUnavailableOrderIsRefundedWithTheWebhookRef() throws Exception {
        long id = rows.order(buyer(), OrderStatus.PAYMENT_UNAVAILABLE);
        assertEquals(null, rows.paymentRef(id));

        send(body(id, "pay_unseen", "SUCCESS", "499.00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUND_RECORDED"));

        assertEquals("PAYMENT_UNAVAILABLE", rows.status(id));
        assertEquals("pay_unseen", jdbc.queryForObject(
                "SELECT payment_ref FROM refunds WHERE order_id = ?", String.class, id));
        assertTrue(rows.outboxAll(id) == 0);
    }
}
