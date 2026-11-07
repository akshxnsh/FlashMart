package com.flashmart.gateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.util.Date;

/**
 * Checks an access token issued by Identity (decisions.md, "Day 8", points 1
 * and 2). Pure CPU, no I/O: safe to call on the event loop.
 *
 * <p>Accepted only if ALL hold: the algorithm is exactly HS256 (the parser's
 * algorithm registry holds nothing else, so {@code "none"}, HS384, HS512 and
 * any RSA or EC name are refused even when the HMAC itself would verify), the
 * signature verifies with {@code JWT_SECRET}, {@code exp} is present and in
 * the future on the injected clock, and {@code sub} and {@code typ} are both
 * present and non-blank. Unsigned tokens are refused because the parser is
 * never told to accept them.
 */
@Component
public class JwtVerifier {

    private final JwtParser parser;

    public JwtVerifier(@Value("${gateway-auth.jwt-secret:}") String secret, Clock clock) {
        byte[] key = HmacSecrets.require("gateway-auth.jwt-secret", "JWT_SECRET", secret);
        this.parser = Jwts.parser()
                .sig().clear().add(Jwts.SIG.HS256).and()
                .verifyWith(new SecretKeySpec(key, "HmacSHA256"))
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /** The token's subject and type. */
    public record VerifiedToken(String subject, String type) {
    }

    /** Thrown for every way a token can be unusable. The message never contains the token. */
    public static final class InvalidTokenException extends RuntimeException {
        InvalidTokenException(String message) {
            super(message, null, false, false);
        }
    }

    public VerifiedToken verify(String token) {
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // e.getMessage() can quote token parts; it is not logged or returned.
            throw new InvalidTokenException("invalid or expired token");
        }
        if (claims.getExpiration() == null) {
            throw new InvalidTokenException("token has no expiry");
        }
        String subject = claims.getSubject();
        Object type = claims.get("typ");
        if (subject == null || subject.isBlank() || !(type instanceof String typ) || typ.isBlank()) {
            throw new InvalidTokenException("token lacks sub or typ");
        }
        return new VerifiedToken(subject, typ);
    }
}
