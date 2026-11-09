package com.flashmart.order.webhook;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * {@code POST /webhooks/payment}, the gateway's callback (decisions.md, "Day 6",
 * point 4). Called by the gateway, never by buyers, so not under {@code /api}.
 *
 * <p>Order of checks is the contract: signature over the raw bytes (401), then
 * parse (400), then the order (404 / 409 / 200). The body is taken as
 * {@code byte[]} so the bytes verified are exactly the bytes received.
 *
 * <p>{@code 200} is returned only after {@link PaymentWebhookService#apply}
 * returns, which is after its transaction committed: a gateway that sees
 * {@code 200} may stop retrying, so the change must already be durable. Any
 * exception rolls back and falls through to Spring's default {@code 500}, which
 * the gateway retries.
 */
/** Hidden from the OpenAPI docs: called by the payment gateway, HMAC-signed. */
@Hidden
@RestController
@RequiredArgsConstructor
public class PaymentWebhookController {

    public static final String SIGNATURE_HEADER = "X-Signature";

    private final WebhookSignature signature;
    private final WebhookParser parser;
    private final PaymentWebhookService service;

    public record WebhookResponse(String outcome) {
    }

    @PostMapping("/webhooks/payment")
    public ResponseEntity<WebhookResponse> receive(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signatureHex,
            @RequestBody(required = false) byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        if (!signature.matches(raw, signatureHex)) {
            return respond(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE");
        }
        Optional<PaymentWebhook> webhook = parser.parse(raw);
        if (webhook.isEmpty()) {
            return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        WebhookOutcome outcome = service.apply(webhook.get());
        HttpStatus status = switch (outcome) {
            case ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TOO_EARLY -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
        return respond(status, outcome.name());
    }

    private static ResponseEntity<WebhookResponse> respond(HttpStatus status, String outcome) {
        return ResponseEntity.status(status).body(new WebhookResponse(outcome));
    }
}
