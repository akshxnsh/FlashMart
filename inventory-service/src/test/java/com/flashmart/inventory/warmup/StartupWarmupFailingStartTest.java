package com.flashmart.inventory.warmup;

import com.flashmart.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Start-up itself, not {@code run()}: the warm-up is ON and the reserve service
 * it calls always throws. If a failure could stop start-up, this context would
 * never load and the class would fail before its first test.
 *
 * <p>It proves something only if the shared database had a flash sale item at
 * start-up (an empty one is skipped before any reserve call), so it is skipped,
 * visibly, when the failing service was never called. The dev database is
 * seeded. {@code @TestConfiguration} is not picked up by component scan.
 */
@SpringBootTest(properties = "startup-warmup.enabled=true")
@Import(StartupWarmupFailingStartTest.Failing.class)
class StartupWarmupFailingStartTest {

    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class Failing {
        @Bean
        @Primary
        ReservationService alwaysFailingReservationService() {
            return request -> {
                CALLS.incrementAndGet();
                throw new IllegalStateException("warm-up failure injected by the test");
            };
        }
    }

    @Test
    void startUpCompletesEvenThoughTheWarmupFailed() {
        assumeTrue(CALLS.get() > 0, "no flash sale item at start-up, so the warm-up never called reserve");
        // The failing service was called during start-up, and this context is up.
        assertTrue(CALLS.get() >= 1);
    }
}
