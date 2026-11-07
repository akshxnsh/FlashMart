package com.flashmart.identity.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The issuer on its own, with a fixed clock. Verification uses jjwt's parser
 * with the same key, as the gateway will; Identity itself never verifies.
 */
class JwtIssuerTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijkl";
    private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    private static JwtIssuer issuerAt(Instant now) {
        return new JwtIssuer(SECRET, TTL, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Claims parseAt(String token, Instant now) {
        return Jwts.parser()
                .verifyWith(JwtIssuer.keyFrom(SECRET))
                .clock(() -> Date.from(now))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Test
    void iatAndExpComeFromTheInjectedClock() {
        Claims claims = parseAt(issuerAt(T0).issueAccessToken(7), T0);
        assertEquals(T0, claims.getIssuedAt().toInstant());
        assertEquals(T0.plus(TTL), claims.getExpiration().toInstant());
        assertEquals("7", claims.getSubject());
    }

    @Test
    void tokenIsAcceptedJustBeforeExpiryAndRejectedJustAfter() {
        String token = issuerAt(T0).issueAccessToken(7);
        assertDoesNotThrow(() -> parseAt(token, T0.plus(TTL).minusSeconds(1)));
        assertThrows(ExpiredJwtException.class, () -> parseAt(token, T0.plus(TTL).plusSeconds(1)));
    }

    @Test
    void aTamperedSignatureIsRejected() {
        String token = issuerAt(T0).issueAccessToken(7);
        int dot = token.lastIndexOf('.');
        char last = token.charAt(dot + 1);
        String tampered = token.substring(0, dot + 1) + (last == 'A' ? 'B' : 'A') + token.substring(dot + 2);
        assertThrows(SignatureException.class, () -> parseAt(tampered, T0));
    }

    @Test
    void aChangedPayloadWithTheOldSignatureIsRejected() {
        String[] parts = issuerAt(T0).issueAccessToken(7).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forged = payload.replace("\"sub\":\"7\"", "\"sub\":\"8\"");
        assertTrue(forged.contains("\"sub\":\"8\""), "the forgery must actually change the subject: " + payload);
        String token = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThrows(SignatureException.class, () -> parseAt(token, T0));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() {
        String other = new JwtIssuer("another-secret-0123456789abcdefghijklmn", TTL,
                Clock.fixed(T0, ZoneOffset.UTC)).issueAccessToken(7);
        assertThrows(SignatureException.class, () -> parseAt(other, T0));
    }

    @Test
    void blankOrMissingSecretIsRefused() {
        for (String secret : new String[]{null, "", "   "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new JwtIssuer(secret, TTL, Clock.systemUTC()));
            assertTrue(e.getMessage().contains("JWT_SECRET"), e.getMessage());
        }
    }

    @Test
    void secretShorterThan32BytesIsRefusedAnd32IsAccepted() {
        assertThrows(IllegalStateException.class, () -> new JwtIssuer("x".repeat(31), TTL, Clock.systemUTC()));
        assertDoesNotThrow(() -> new JwtIssuer("x".repeat(32), TTL, Clock.systemUTC()));
    }

    @Test
    void secretLengthIsCountedInBytes() {
        // 16 two-byte characters = 32 bytes: accepted; 15 = 30 bytes: refused.
        assertDoesNotThrow(() -> new JwtIssuer("é".repeat(16), TTL, Clock.systemUTC()));
        assertThrows(IllegalStateException.class, () -> new JwtIssuer("é".repeat(15), TTL, Clock.systemUTC()));
    }
}
