package com.flashmart.paymentstub;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    /** {@code amount} is a decimal string here, not integer minor units as real gateways use. */
    public record PaymentResponse(String paymentRef, long orderId, String amount, String status) {
    }

    private final PaymentStore store;
    private final ModeState modes;
    private final AutoWebhookState autoWebhooks;
    private final WebhookSender webhooks;
    private final WebhookSettings webhookSettings;

    public PaymentController(PaymentStore store, ModeState modes, AutoWebhookState autoWebhooks,
                             WebhookSender webhooks, WebhookSettings webhookSettings) {
        this.store = store;
        this.modes = modes;
        this.autoWebhooks = autoWebhooks;
        this.webhooks = webhooks;
        this.webhookSettings = webhookSettings;
    }

    @PostMapping("/api/payments/initiate")
    public ResponseEntity<?> initiate(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                      @RequestBody InitiateRequest body) throws InterruptedException {
        if (key == null || key.isBlank()) {
            return invalid("Idempotency-Key header is required");
        }
        ValidRequest request;
        try {
            request = ValidRequest.from(body);
        } catch (IllegalArgumentException e) {
            return invalid(e.getMessage());
        }

        ModeState.Current mode = modes.get();
        switch (mode.mode()) {
            case ERROR_5XX -> {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ErrorResponse("STUB_ERROR", "the stub is set to fail with 500"));
            }
            case REJECT_4XX -> {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("PAYMENT_REJECTED", "the stub is set to reject with 400"));
            }
            case SLOW_BEFORE -> Thread.sleep(mode.delayMs());
            default -> { }
        }

        PaymentStore.Result stored = store.createOrFind(key, request);
        Payment payment = stored.payment();
        if (!payment.request().equals(request)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("IDEMPOTENCY_KEY_REUSED",
                    "this Idempotency-Key was used with a different request"));
        }
        if (stored.created()) {
            autoWebhook(payment);
        }

        if (mode.mode() == Mode.SLOW_AFTER) {
            Thread.sleep(mode.delayMs());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PaymentResponse(
                payment.paymentRef(), payment.request().orderId(),
                payment.request().amount().toPlainString(), payment.status()));
    }

    /** Only for a payment this call created: a replayed initiate is not a new payment and sends nothing. */
    private void autoWebhook(Payment payment) {
        AutoWebhookState.Current auto = autoWebhooks.get();
        if (auto.status() == AutoWebhookState.Status.OFF) {
            return;
        }
        WebhookMessage message = WebhookMessage.of(payment, auto.status().name());
        if (auto.timing() == AutoWebhookState.Timing.BEFORE_REPLY) {
            webhooks.firstAttemptNowRestLater(message);
        } else {
            webhooks.deliverLater(message, webhookSettings.afterReplyDelayMs());
        }
    }

    private static ResponseEntity<ErrorResponse> invalid(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", message));
    }
}
