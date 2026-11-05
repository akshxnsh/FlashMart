package com.flashmart.order.outbox;

import java.util.Map;
import java.util.Optional;

/**
 * One topic per event (decisions.md, "Day 6", point 6). The keys are exactly
 * the values V5's {@code ck_outbox_event_type_known} allows, so a row the
 * database accepted always has a topic; {@link #topicFor} returning empty means
 * the CHECK and this map have drifted apart.
 */
public final class OutboxTopics {

    public static final String PAYMENT_CONFIRMED = "payment-confirmed";
    public static final String ORDER_CANCELLED = "order-cancelled";

    private static final Map<String, String> BY_EVENT_TYPE = Map.of(
            "PaymentConfirmed", PAYMENT_CONFIRMED,
            "OrderCancelled", ORDER_CANCELLED);

    private OutboxTopics() {
    }

    public static Optional<String> topicFor(String eventType) {
        return Optional.ofNullable(BY_EVENT_TYPE.get(eventType));
    }
}
