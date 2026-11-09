package com.flashmart.inventory.reconciler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs one reconciler pass every {@code reconciler.interval-ms} after the
 * previous one finished (fixed delay: passes in one instance never overlap;
 * passes in two instances may, and the status-guarded writes keep that
 * correct). {@code reconciler.enabled=false} removes this bean (tests);
 * {@link StaleReservationReconciler} itself still exists and can be called.
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class StaleReservationReconcilerScheduling {

    private final StaleReservationReconciler reconciler;

    public StaleReservationReconcilerScheduling(StaleReservationReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${reconciler.interval-ms}")
    public void pass() {
        try {
            StaleReservationReconciler.Result r = reconciler.runOnce();
            if (r.rows() > 0) {
                log.info("reconciler pass: {} stale row(s), {} order(s) asked, {} confirmed, {} released, "
                        + "{} alert(s){}", r.rows(), r.ordersAsked(), r.confirmed(), r.released(), r.alerts(),
                        r.stoppedAt() == null ? "" : ", stopped at order " + r.stoppedAt());
            }
        } catch (RuntimeException e) {
            // The read or a release/confirm failed (for example the database is down).
            // Whatever committed stays; the next pass retries the rest.
            log.error("reconciler pass failed; next pass retries", e);
        }
    }
}
