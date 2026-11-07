package com.flashmart.order.auth;

import com.flashmart.order.OrderServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The real Order application refuses to START with a blank or short
 * GATEWAY_SIGNING_SECRET. Command-line arguments outrank the test secret in
 * config/application.yml. Port 0 so a running Order on 8083 does not interfere.
 */
class GatewaySigningSecretStartupTest {

    private static String startFailure(String arg) {
        Throwable t = assertThrows(Throwable.class, () -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(OrderServiceApplication.class)
                    .run("--server.port=0", arg)) {
                // started: the assertion fails
            }
        }, "Order started with " + arg);
        StringBuilder messages = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            messages.append(c.getMessage()).append(" | ");
        }
        return messages.toString();
    }

    @Test
    void blankSecretStopsStartup() {
        assertThat(startFailure("--gateway-identity.signing-secret=")).contains("signing-secret is blank");
    }

    @Test
    void spacesOnlySecretStopsStartupAsBlank() {
        assertThat(startFailure("--gateway-identity.signing-secret=" + " ".repeat(40)))
                .contains("signing-secret is blank");
    }

    @Test
    void shortSecretStopsStartup() {
        assertThat(startFailure("--gateway-identity.signing-secret=" + "x".repeat(31)))
                .contains("signing-secret is 31 bytes");
    }
}
