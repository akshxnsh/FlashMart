package com.flashmart.order.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs one relay pass every {@code outbox.relay.poll-ms} after the previous
 * one finished (fixed delay, so passes in one instance never overlap).
 * {@code outbox.relay.enabled=false} removes this bean, and with it all
 * scheduling; the {@link OutboxRelay} itself still exists and can be called.
 */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayScheduling {

    private final OutboxRelay relay;

    @Scheduled(fixedDelayString = "${outbox.relay.poll-ms}")
    public void poll() {
        try {
            OutboxRelay.Result result = relay.runOnce();
            if (result.published() > 0) {
                log.debug("outbox relay published {} of {} claimed", result.published(), result.claimed());
            }
        } catch (RuntimeException e) {
            // The pass rolled back: nothing it sent is marked, so it is re-sent next time.
            log.error("outbox relay pass failed and rolled back; its rows will be re-sent", e);
        }
    }
}
