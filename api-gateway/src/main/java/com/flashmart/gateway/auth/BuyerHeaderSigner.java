package com.flashmart.gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Signs the buyer id the Gateway forwards to Order (decisions.md, "Day 8",
 * point 5): lowercase hex of HMAC-SHA256({@code GATEWAY_SIGNING_SECRET},
 * {@code buyerId + "." + epochSeconds}). The {@code "."} is the delimiter the
 * decision requires: without it buyer 1 at time 23 and buyer 12 at time 3 would
 * sign the same text "123". Order recomputes exactly this.
 */
@Component
public class BuyerHeaderSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public BuyerHeaderSigner(@Value("${gateway-auth.signing-secret:}") String secret) {
        this.key = new SecretKeySpec(
                HmacSecrets.require("gateway-auth.signing-secret", "GATEWAY_SIGNING_SECRET", secret), ALGORITHM);
    }

    public String sign(String buyerId, long epochSeconds) {
        try {
            // A new Mac per call: Mac is not thread-safe, and this runs on
            // several event-loop threads at once.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] signed = mac.doFinal((buyerId + "." + epochSeconds).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(signed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
