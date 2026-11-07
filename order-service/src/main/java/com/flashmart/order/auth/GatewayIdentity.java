package com.flashmart.order.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Checks the buyer identity the Gateway signed (decisions.md, "Day 8", point 5).
 * Order no longer trusts a bare {@code X-Buyer-Id}: the header is accepted only
 * with {@code X-Auth-Timestamp} and {@code X-Auth-Signature}, where the
 * signature is the lowercase-hex HMAC-SHA256, keyed by
 * {@code GATEWAY_SIGNING_SECRET}, of exactly {@code "<X-Buyer-Id>.<X-Auth-Timestamp>"}
 * as received.
 *
 * <p>Checks, in order: all three headers present; the signature matches
 * (constant-time {@link MessageDigest#isEqual} on the decoded bytes, so neither
 * timing nor letter case reveals how much of a forgery was right); the
 * timestamp is at most {@code max-age} old and at most {@code max-future}
 * ahead of Order's clock; the buyer id is a positive whole number. Any failure
 * is one {@link InvalidIdentityException}, answered {@code 401}.
 *
 * <p>Accepted limit (decisions.md): a captured signed request can be replayed
 * for up to {@code max-age}.
 */
@Component
public class GatewayIdentity {

    static final int MIN_SECRET_BYTES = 32;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;
    private final Clock clock;
    private final long maxAgeSeconds;
    private final long maxFutureSeconds;

    public GatewayIdentity(@Value("${gateway-identity.signing-secret:}") String secret,
                           @Value("${gateway-identity.max-age}") Duration maxAge,
                           @Value("${gateway-identity.max-future}") Duration maxFuture,
                           Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "gateway-identity.signing-secret is blank: set GATEWAY_SIGNING_SECRET (see .env.example)");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("gateway-identity.signing-secret is " + bytes.length
                    + " bytes; at least " + MIN_SECRET_BYTES + " are required (set GATEWAY_SIGNING_SECRET)");
        }
        if (maxAge == null || maxAge.isNegative() || maxFuture == null || maxFuture.isNegative()) {
            throw new IllegalStateException("gateway-identity.max-age and max-future must not be negative");
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
        this.clock = clock;
        this.maxAgeSeconds = maxAge.toSeconds();
        this.maxFutureSeconds = maxFuture.toSeconds();
    }

    public static final class InvalidIdentityException extends RuntimeException {
        InvalidIdentityException(String message) {
            super(message, null, false, false);
        }
    }

    /** The verified buyer id, or {@link InvalidIdentityException}. */
    public long verify(String buyerId, String timestamp, String signatureHex) {
        if (isBlank(buyerId) || isBlank(timestamp) || isBlank(signatureHex)) {
            throw new InvalidIdentityException("missing gateway identity headers");
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signatureHex.trim());
        } catch (IllegalArgumentException notHex) {
            throw new InvalidIdentityException("bad gateway signature");
        }
        if (!MessageDigest.isEqual(sign(buyerId + "." + timestamp), given)) {
            throw new InvalidIdentityException("bad gateway signature");
        }
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new InvalidIdentityException("bad gateway timestamp");
        }
        long now = clock.instant().getEpochSecond();
        if (now - signedAt > maxAgeSeconds) {
            throw new InvalidIdentityException("gateway signature is too old");
        }
        if (signedAt - now > maxFutureSeconds) {
            throw new InvalidIdentityException("gateway timestamp is in the future");
        }
        long buyer;
        try {
            buyer = Long.parseLong(buyerId);
        } catch (NumberFormatException e) {
            throw new InvalidIdentityException("buyer id is not a number");
        }
        if (buyer <= 0) {
            throw new InvalidIdentityException("buyer id must be positive");
        }
        return buyer;
    }

    private byte[] sign(String text) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
