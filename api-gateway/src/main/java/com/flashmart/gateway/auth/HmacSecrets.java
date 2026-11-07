package com.flashmart.gateway.auth;

import java.nio.charset.StandardCharsets;

/**
 * The start-up rule for both Gateway secrets, the same as Identity's
 * {@code JWT_SECRET} rule: blank is refused (an empty HMAC key is still a key,
 * and anyone could sign with it), and so is anything under 32 bytes (HMAC-SHA256
 * wants a 256-bit key; a short one is guessable).
 */
final class HmacSecrets {

    static final int MIN_BYTES = 32;

    private HmacSecrets() {
    }

    /** The secret's UTF-8 bytes, or an exception that stops start-up. Never includes the value in the message. */
    static byte[] require(String property, String envVar, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(property + " is blank: set " + envVar + " (see .env.example)");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException(property + " is " + bytes.length + " bytes; at least "
                    + MIN_BYTES + " are required (set " + envVar + ")");
        }
        return bytes;
    }
}
