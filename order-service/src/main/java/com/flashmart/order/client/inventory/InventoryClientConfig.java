package com.flashmart.order.client.inventory;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * Per-client Feign configuration for {@link InventoryClient}. Deliberately not
 * a {@code @Configuration}: component scan must not pick it up and apply it to
 * every Feign client.
 *
 * <p>No in-call retries on Day 4, stated here rather than inherited from a
 * default. A retry multiplies the worst case of one reserve call, and the
 * {@code CREATED} deadline is derived from that worst case; it is re-derived on
 * Day 5 with the retry attempts.
 */
public class InventoryClientConfig {

    @Bean
    Retryer inventoryRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
