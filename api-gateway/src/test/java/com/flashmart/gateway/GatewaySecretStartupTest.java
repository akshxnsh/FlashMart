package com.flashmart.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The real application refuses to START with a bad secret. Command-line
 * arguments outrank the test secrets in config/application.yml. Port 0 so a
 * running Gateway on 8080 does not interfere.
 */
class GatewaySecretStartupTest {

    private static String startFailure(String arg) {
        Throwable t = assertThrows(Throwable.class, () -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(ApiGatewayApplication.class)
                    .run("--server.port=0", arg)) {
                // started: the assertion fails
            }
        }, "the gateway started with " + arg);
        StringBuilder messages = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            messages.append(c.getMessage()).append(" | ");
        }
        return messages.toString();
    }

    @Test
    void blankJwtSecretStopsStartup() {
        assertThat(startFailure("--gateway-auth.jwt-secret=")).contains("gateway-auth.jwt-secret is blank");
    }

    @Test
    void spacesOnlyJwtSecretStopsStartupAsBlank() {
        assertThat(startFailure("--gateway-auth.jwt-secret=" + " ".repeat(40))).contains("gateway-auth.jwt-secret is blank");
    }

    @Test
    void shortJwtSecretStopsStartup() {
        assertThat(startFailure("--gateway-auth.jwt-secret=" + "x".repeat(31))).contains("gateway-auth.jwt-secret is 31 bytes");
    }

    @Test
    void blankSigningSecretStopsStartup() {
        assertThat(startFailure("--gateway-auth.signing-secret=")).contains("gateway-auth.signing-secret is blank");
    }

    @Test
    void spacesOnlySigningSecretStopsStartupAsBlank() {
        assertThat(startFailure("--gateway-auth.signing-secret=" + " ".repeat(40))).contains("gateway-auth.signing-secret is blank");
    }

    @Test
    void shortSigningSecretStopsStartup() {
        assertThat(startFailure("--gateway-auth.signing-secret=" + "x".repeat(31)))
                .contains("gateway-auth.signing-secret is 31 bytes");
    }
}
