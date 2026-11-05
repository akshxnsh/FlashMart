package com.flashmart.order.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Turns an already-verified body into a {@link PaymentWebhook}, or nothing
 * (a {@code 400}). Strict on purpose: anything this rejects would otherwise
 * reach a CAS or a refund row as a guess.
 *
 * <ul>
 *   <li>{@code amount} must be a JSON <em>string</em> (money crosses every
 *       boundary as a string, never a float), a positive decimal with at most
 *       2 places and 12 integer digits, i.e. one that fits {@code NUMERIC(14,2)}
 *       exactly. A third decimal place would be silently rounded when written
 *       into {@code refunds.amount}, so it is rejected rather than stored as a
 *       different number.</li>
 *   <li>{@code payment_ref} is non-blank and at most 255 characters, the
 *       column's width, so it can never fail the refund insert.</li>
 *   <li>{@code order_id} is a positive whole number.</li>
 * </ul>
 * Unknown fields are ignored.
 */
@Component
@RequiredArgsConstructor
public class WebhookParser {

    private static final int MAX_REF_LENGTH = 255;
    private static final int MAX_SCALE = 2;
    private static final int MAX_INTEGER_DIGITS = 12;

    private final ObjectMapper json;

    public Optional<PaymentWebhook> parse(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        JsonNode orderId = root.get("order_id");
        JsonNode ref = root.get("payment_ref");
        JsonNode status = root.get("status");
        JsonNode amount = root.get("amount");
        if (orderId == null || !orderId.isIntegralNumber() || !orderId.canConvertToLong() || orderId.longValue() <= 0
                || ref == null || !ref.isString() || ref.stringValue().isBlank()
                || ref.stringValue().length() > MAX_REF_LENGTH
                || status == null || !status.isString()
                || amount == null || !amount.isString()) {
            return Optional.empty();
        }
        PaymentWebhook.Status parsedStatus;
        try {
            parsedStatus = PaymentWebhook.Status.valueOf(status.stringValue());
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        return money(amount.stringValue())
                .map(m -> new PaymentWebhook(orderId.longValue(), ref.stringValue(), parsedStatus, m));
    }

    private static Optional<BigDecimal> money(String text) {
        BigDecimal value;
        try {
            value = new BigDecimal(text.trim());
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
        BigDecimal normal = value.stripTrailingZeros();
        int scale = Math.max(normal.scale(), 0);
        int integerDigits = normal.precision() - normal.scale();
        if (value.signum() <= 0 || scale > MAX_SCALE || integerDigits > MAX_INTEGER_DIGITS) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
