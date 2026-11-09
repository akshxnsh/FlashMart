package com.flashmart.inventory.reconciler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * All six reconciler counters exist at zero as soon as the reconciler is built
 * (start-up), before any pass, so Prometheus shows them from the first scrape.
 * A fresh registry, so no other test's counts are mixed in.
 */
class ReconcilerCountersTest {

    @Test
    void everyAlertReasonAndEveryActionIsRegisteredAtZero() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ReconcilerSettings settings = new ReconcilerSettings(50, 0, 0, 0, 0, "http://127.0.0.1:1", 1000, 2000);
        new StaleReservationReconciler(null, null, null, settings, meters);

        Map<String, String> expected = Map.of(
                "order_live", "reason", "unknown_status", "reason", "order_not_found", "reason",
                "order_unreachable", "reason", "confirmed", "action", "released", "action");
        expected.forEach((value, tag) -> {
            String name = tag.equals("reason") ? "inventory.reconciler.alerts" : "inventory.reconciler.actions";
            Counter c = meters.find(name).tag(tag, value).counter();
            assertNotNull(c, name + "{" + tag + "=" + value + "}");
            assertEquals(0.0, c.count(), name + "{" + tag + "=" + value + "}");
        });
        assertEquals(6, meters.getMeters().size(), "exactly these six, no other meter");
    }
}
