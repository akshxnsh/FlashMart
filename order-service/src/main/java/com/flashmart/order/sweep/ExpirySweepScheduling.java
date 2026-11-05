package com.flashmart.order.sweep;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs one sweep tick (passes back to back while batches come back full,
 * see {@link ExpirySweep#drain}) every {@code order.sweep.interval-ms} after the previous
 * one finished (fixed delay, so ticks in one instance never overlap; ticks
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
public class ExpirySweepScheduling {

    private final ExpirySweep sweep;
    private final int maxPassesPerTick;

    public ExpirySweepScheduling(ExpirySweep sweep, @Value("${order.sweep.max-passes-per-tick}") int maxPassesPerTick) {
        if (maxPassesPerTick < 1) {
            throw new IllegalArgumentException("order.sweep.max-passes-per-tick must be at least 1");
        }
        this.sweep = sweep;
        this.maxPassesPerTick = maxPassesPerTick;
    }

    @Scheduled(fixedDelayString = "${order.sweep.interval-ms}")
    public void sweep() {
        try {
            ExpirySweep.Drain result = sweep.drain(maxPassesPerTick);
            if (result.picked() > 0) {
                log.info("expiry sweep: {} pass(es), {} due, {} expired, {} failed", result.passes(),
                        result.picked(), result.expired(), result.failed());
            }
        } catch (RuntimeException e) {
            // The SELECT itself failed (for example the database is down). Nothing was written.
            log.error("expiry sweep pass failed before expiring anything; next pass retries", e);
        }
    }
}
