package com.flashmart.order.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The relay's three numbers (decisions.md, "Day 6", point 6): PROVISIONAL,
 * unmeasured, recheck on Day 9. The producer's own timeouts are derived from
 * {@link #ackTimeoutMs()} in {@link OutboxKafkaConfig}, not set separately.
 */
@Component
public class RelaySettings {

    private final int batchSize;
    private final long ackTimeoutMs;

    public RelaySettings(@Value("${outbox.relay.batch-size}") int batchSize,
                         @Value("${outbox.relay.ack-timeout-ms}") long ackTimeoutMs) {
        if (batchSize <= 0 || ackTimeoutMs <= 0) {
            throw new IllegalStateException("outbox.relay.batch-size and ack-timeout-ms must be > 0, were "
                    + batchSize + " and " + ackTimeoutMs);
        }
        this.batchSize = batchSize;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    public int batchSize() {
        return batchSize;
    }

    public long ackTimeoutMs() {
        return ackTimeoutMs;
    }
}
