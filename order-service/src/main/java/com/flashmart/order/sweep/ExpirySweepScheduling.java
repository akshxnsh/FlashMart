package com.flashmart.order.sweep;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs one sweep pass every {@code order.sweep.interval-ms} after the previous
 * one finished (fixed delay, so passes in one instance never overlap; passes
 * in two instances may, and the CAS keeps that correct). Its own
 * {@code @EnableScheduling}: the relay's is removed with the relay when
 * {@code outbox.relay.enabled=false}, and the sweep must not depend on it.
 * {@code order.sweep.enabled=false} removes this bean; {@link ExpirySweep}
 * itself still exists and can be called.
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "order.sweep.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ExpirySweepScheduling {

    private final ExpirySweep sweep;

    @Scheduled(fixedDelayString = "${order.sweep.interval-ms}")
    public void sweep() {
        try {
            ExpirySweep.Result result = sweep.runOnce();
            if (result.picked() > 0) {
                log.info("expiry sweep: {} due, {} expired, {} failed", result.picked(), result.expired(),
                        result.failed());
            }
        } catch (RuntimeException e) {
            // The SELECT itself failed (for example the database is down). Nothing was written.
            log.error("expiry sweep pass failed before expiring anything; next pass retries", e);
        }
    }
}
