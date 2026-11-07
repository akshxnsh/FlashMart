package com.flashmart.identity.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Makes and hashes refresh tokens. A bean (not static) so a test can force a
 * specific token and make the insert fail.
 */
@Component
public class RefreshTokens {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    /** 32 random bytes, base64url without padding (43 characters). Returned to the buyer once, never stored. */
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 as 64 lowercase hex characters: what {@code token_hash} stores.
     * A fast, deterministic hash (not bcrypt) because the row is found by
     * {@code WHERE token_hash = ?}; safe because the token is 256 random bits,
     * unlike a password. Same reasoning as {@code api_keys.key_hash}.
     */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
