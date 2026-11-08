package com.flashmart.order;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs requests the way the API Gateway does: X-Buyer-Id, X-Auth-Timestamp
 * (epoch seconds) and X-Auth-Signature = hex HMAC-SHA256 of "buyerId.timestamp"
 * with the test secret in config/application.yml. Computed here, independently
 * of Order's own {@code GatewayIdentity}, so a bug there cannot hide itself.
 */
public final class TestGatewayHeaders {

    public static final String SECRET = "test-only-gateway-signing-secret-0123456";

    private TestGatewayHeaders() {
    }

    public static String signature(String buyerId, String timestamp) {
        return hmacHex(SECRET, buyerId + "." + timestamp);
    }

    public static String hmacHex(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Valid identity headers for {@code buyerId}, signed now. */
    public static MockHttpServletRequestBuilder signedAs(MockHttpServletRequestBuilder request, Object buyerId) {
        return signedAs(request, buyerId.toString(), Instant.now().getEpochSecond());
    }

    public static MockHttpServletRequestBuilder signedAs(MockHttpServletRequestBuilder request, String buyerId,
                                                         long epochSeconds) {
        String ts = Long.toString(epochSeconds);
        return request.header("X-Buyer-Id", buyerId)
                .header("X-Auth-Timestamp", ts)
                .header("X-Auth-Signature", signature(buyerId, ts));
    }
}
