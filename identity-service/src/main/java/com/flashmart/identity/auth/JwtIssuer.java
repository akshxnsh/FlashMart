package com.flashmart.identity.auth;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Signs buyer access tokens (decisions.md, "Day 8", points 1 and 2).
 *
 * <p>HS256 with {@code JWT_SECRET}, which only Identity (signs) and the
 * gateway (verifies) hold. Claims are exactly {@code sub} (the user id as a
 * string), {@code typ} ({@code "user"}), {@code iat} and {@code exp}: nothing
 * else, so a token leaks nothing but an id. {@code typ} exists because brand 7
 * and user 7 share an id; only user tokens are issued.
 *
 * <p>Start-up fails on a blank secret (an empty HMAC key is still a key, and
 * anyone could sign with it) and on one shorter than 32 bytes (HS256 needs a
 * 256-bit key; a short one is guessable). Identity never verifies access
 * tokens itself: that is the gateway's job.
 */
@Component
public class JwtIssuer {

    public static final String TYPE_CLAIM = "typ";
    public static final String USER_TYPE = "user";
    static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTtl;
    private final Clock clock;

    public JwtIssuer(@Value("${identity.jwt.secret:}") String secret,
                     @Value("${identity.jwt.access-ttl}") Duration accessTtl,
                     Clock clock) {
        this.key = keyFrom(secret);
        if (accessTtl == null || accessTtl.isNegative() || accessTtl.isZero()) {
            throw new IllegalStateException("identity.jwt.access-ttl must be positive");
        }
        this.accessTtl = accessTtl;
        this.clock = clock;
    }

    /** The HS256 key for {@code secret}; package-visible so tests verify with exactly the key used to sign. */
    static SecretKey keyFrom(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("identity.jwt.secret is blank: set JWT_SECRET (see .env.example)");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("identity.jwt.secret is " + bytes.length
                    + " bytes; HS256 needs at least " + MIN_SECRET_BYTES + " (set JWT_SECRET)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    public String issueAccessToken(long userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim(TYPE_CLAIM, USER_TYPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Access-token lifetime in seconds, for the {@code expiresIn} field. */
    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
