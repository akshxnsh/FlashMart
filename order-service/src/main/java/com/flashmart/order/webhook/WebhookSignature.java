package com.flashmart.order.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 of the raw webhook body, hex in {@code X-Signature}
 * (decisions.md, "Day 6", point 4).
 *
 * <p>Checked over the exact bytes received, before any JSON parsing: no parser
 * runs on unauthenticated input, and re-serialising JSON can never change the
 * bytes being verified. The compare is constant-time ({@link MessageDigest#isEqual})
 * on the decoded bytes, so neither the timing nor hex letter case leaks
 * how much of a forged signature was right.
 *
 * <p>The secret comes from {@code PAYMENT_WEBHOOK_SECRET}. A blank secret fails
 * startup: an empty HMAC key is still a valid key, and anyone could sign with it.
 */
@Component
public class WebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public WebhookSignature(@Value("${payment-webhook.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "payment-webhook.secret is blank: set PAYMENT_WEBHOOK_SECRET (see .env.example)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** True only if {@code signatureHex} is the HMAC of exactly {@code body}. Null or non-hex is false. */
    public boolean matches(byte[] body, String signatureHex) {
        if (signatureHex == null || signatureHex.isBlank()) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signatureHex.trim());
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        return MessageDigest.isEqual(sign(body), given);
    }

    /** The raw HMAC of {@code body}. Package-visible for tests that need a valid signature. */
    byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
