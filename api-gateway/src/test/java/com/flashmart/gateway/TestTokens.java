package com.flashmart.gateway;

import io.jsonwebtoken.Jwts;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/** Tokens and signatures built independently of the Gateway's own code, with the test secrets. */
public final class TestTokens {

    public static final String JWT_SECRET = "test-only-jwt-secret-0123456789-0123456789-0123456789-abcdefghij";
    public static final String SIGNING_SECRET = "test-only-gateway-signing-secret-0123456789";
    public static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private static final SecretKeySpec KEY =
            new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private TestTokens() {
    }

    /** What Identity issues: HS256, sub, typ, iat, exp. */
    public static String token(String sub, String typ, Instant expires) {
        var b = Jwts.builder().issuedAt(Date.from(NOW.minusSeconds(60))).expiration(Date.from(expires));
        if (sub != null) {
            b.subject(sub);
        }
        if (typ != null) {
            b.claim("typ", typ);
        }
        return b.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    public static String user(long id) {
        return token(Long.toString(id), "user", NOW.plusSeconds(900));
    }

    /** The same claims signed HS512 with the same secret (64 bytes, so the HMAC itself is valid). */
    public static String hs512User(long id) {
        SecretKeySpec key512 = new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        return Jwts.builder().subject(Long.toString(id)).claim("typ", "user")
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(900)))
                .signWith(key512, Jwts.SIG.HS512).compact();
    }

    /** An unsigned token: header {"alg":"none"}, valid claims, empty signature. */
    public static String algNone(long id) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = enc.encodeToString(("{\"sub\":\"" + id + "\",\"typ\":\"user\",\"iat\":"
                + NOW.getEpochSecond() + ",\"exp\":" + NOW.plusSeconds(900).getEpochSecond() + "}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + ".";
    }

    /** HMAC-SHA256 hex, computed here and not by the Gateway's signer. */
    public static String hmacHex(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
