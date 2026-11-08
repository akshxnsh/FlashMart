package com.flashmart.order.auth;

import com.flashmart.order.TestGatewayHeaders;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The window edges and header forms, on a fixed clock. */
class GatewayIdentityTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final long T = NOW.getEpochSecond();

    private final GatewayIdentity identity = new GatewayIdentity(TestGatewayHeaders.SECRET,
            Duration.ofSeconds(60), Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC));

    private long verifyAt(String buyer, long ts) {
        String t = Long.toString(ts);
        return identity.verify(buyer, t, TestGatewayHeaders.signature(buyer, t));
    }

    @Test
    void exactly60SecondsOldIsAcceptedAnd61IsNot() {
        assertThat(verifyAt("7", T - 60)).isEqualTo(7);
        assertThatThrownBy(() -> verifyAt("7", T - 61)).hasMessage("gateway signature is too old");
    }

    @Test
    void exactly5SecondsAheadIsAcceptedAnd6IsNot() {
        assertThat(verifyAt("7", T + 5)).isEqualTo(7);
        assertThatThrownBy(() -> verifyAt("7", T + 6)).hasMessage("gateway timestamp is in the future");
    }

    @Test
    void upperCaseHexIsTheSameSignature() {
        String t = Long.toString(T);
        assertThat(identity.verify("7", t, TestGatewayHeaders.signature("7", t).toUpperCase())).isEqualTo(7);
    }

    @Test
    void aSignedButNonPositiveOrNonNumericBuyerIsRefused() {
        assertThatThrownBy(() -> verifyAt("0", T)).hasMessage("buyer id must be positive");
        assertThatThrownBy(() -> verifyAt("-3", T)).hasMessage("buyer id must be positive");
        assertThatThrownBy(() -> verifyAt("abc", T)).hasMessage("buyer id is not a number");
    }

    @Test
    void theDelimiterSeparatesBuyerFromTimestamp() {
        // Buyer 1 at time ...23 and buyer 12 at time ...3 must not share a signature.
        String sig = TestGatewayHeaders.signature("1", T + "");
        assertThatThrownBy(() -> identity.verify("1" + Long.toString(T).charAt(0),
                Long.toString(T).substring(1), sig)).hasMessage("bad gateway signature");
    }

    @Test
    void blankOrShortSecretIsRefused() {
        Clock c = Clock.fixed(NOW, ZoneOffset.UTC);
        Duration d = Duration.ofSeconds(60);
        assertThatThrownBy(() -> new GatewayIdentity("", d, d, c)).hasMessageContaining("is blank");
        assertThatThrownBy(() -> new GatewayIdentity(" ".repeat(40), d, d, c)).hasMessageContaining("is blank");
        assertThatThrownBy(() -> new GatewayIdentity("x".repeat(31), d, d, c)).hasMessageContaining("is 31 bytes");
    }
}
