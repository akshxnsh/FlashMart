package com.flashmart.paymentstub;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Local-only webhook controls, for demos and failure runs. Not authenticated,
 * like {@code /admin/mode}: the stub listens on 127.0.0.1 only.
 *
 * <ul>
 *   <li>{@code POST /admin/webhook}: send a webhook now. Fields left out are
 *       taken from the stored payment found by {@code payment_ref}, else by
 *       {@code order_id}; any field given overrides it (a wrong amount, another
 *       ref, another order id). With no stored payment, {@code order_id},
 *       {@code payment_ref} and {@code amount} are all required. {@code times}
 *       repeats the whole delivery (redelivery). Synchronous: each delivery
 *       retries until 2xx or the give-up window ends before the next starts.</li>
 *   <li>{@code POST /admin/auto-webhook}: whether new payments get a webhook on
 *       their own ({@link AutoWebhookState}).</li>
 * </ul>
 */
@RestController
public class WebhookAdminController {

    private static final int MAX_TIMES = 20;

    public record SendRequest(@JsonProperty("order_id") Long orderId,
                              @JsonProperty("payment_ref") String paymentRef,
                              String status,
                              String amount,
                              Integer times) {
    }

    public record SendResponse(WebhookMessage message, List<WebhookSender.Delivery> deliveries) {
    }

    public record AutoRequest(AutoWebhookState.Status status, AutoWebhookState.Timing timing) {
    }

    private final PaymentStore store;
    private final WebhookSender sender;
    private final AutoWebhookState auto;

    public WebhookAdminController(PaymentStore store, WebhookSender sender, AutoWebhookState auto) {
        this.store = store;
        this.sender = sender;
        this.auto = auto;
    }

    @PostMapping("/admin/webhook")
    public ResponseEntity<?> send(@RequestBody SendRequest request) {
        int times = request.times() == null ? 1 : request.times();
        String status = request.status() == null ? "SUCCESS" : request.status();
        if (times < 1 || times > MAX_TIMES || !(status.equals("SUCCESS") || status.equals("FAILED"))) {
            return invalid("times must be 1.." + MAX_TIMES + " and status SUCCESS or FAILED");
        }
        Optional<Payment> base = Optional.ofNullable(request.paymentRef()).flatMap(store::findByRef)
                .or(() -> Optional.ofNullable(request.orderId()).flatMap(store::findByOrderId));
        Long orderId = request.orderId() != null ? request.orderId()
                : base.map(p -> p.request().orderId()).orElse(null);
        String ref = request.paymentRef() != null ? request.paymentRef() : base.map(Payment::paymentRef).orElse(null);
        String amount = request.amount() != null ? request.amount()
                : base.map(p -> p.request().amount().toPlainString()).orElse(null);
        if (orderId == null || ref == null || amount == null) {
            return invalid("no stored payment matches; give order_id, payment_ref and amount");
        }
        WebhookMessage message = new WebhookMessage(orderId, ref, status, amount);
        List<WebhookSender.Delivery> deliveries = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            deliveries.add(sender.deliver(message));
        }
        return ResponseEntity.ok(new SendResponse(message, deliveries));
    }

    @PostMapping("/admin/auto-webhook")
    public ResponseEntity<?> auto(@RequestBody AutoRequest request) {
        if (request.status() == null) {
            return invalid("status is required: OFF, SUCCESS or FAILED");
        }
        auto.set(request.status(), request.timing() == null ? AutoWebhookState.Timing.AFTER_REPLY : request.timing());
        return ResponseEntity.ok(auto.get());
    }

    private static ResponseEntity<ErrorResponse> invalid(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", message));
    }
}
