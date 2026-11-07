package com.flashmart.identity.auth;

import com.flashmart.identity.IdentityServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real application refuses to START with a bad {@code JWT_SECRET}, not
 * just the issuer's constructor. Command-line arguments outrank the test
 * secret in {@code config/application.yml}. Port 0 so a running Identity on
 * 8081 does not interfere.
 */
class JwtSecretStartupTest {

    private static Throwable startWith(String secretArg) {
        return assertThrows(Throwable.class, () -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(IdentityServiceApplication.class)
                    .run("--server.port=0", secretArg)) {
                // started: the assertion below fails
            }
        }, "the application started with " + secretArg);
    }

    private static boolean causedByJwtSecret(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof IllegalStateException && c.getMessage() != null
                    && c.getMessage().contains("identity.jwt.secret")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void blankSecretStopsStartup() {
        Throwable failure = startWith("--identity.jwt.secret=");
        assertTrue(causedByJwtSecret(failure), "failed, but not because of the secret: " + failure);
    }

    @Test
    void secretShorterThan32BytesStopsStartup() {
        Throwable failure = startWith("--identity.jwt.secret=" + "x".repeat(31));
        assertTrue(causedByJwtSecret(failure), "failed, but not because of the secret: " + failure);
    }
}
