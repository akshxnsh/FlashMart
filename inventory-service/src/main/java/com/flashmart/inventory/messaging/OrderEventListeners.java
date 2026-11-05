package com.flashmart.inventory.messaging;

import com.flashmart.inventory.service.ReservationEventService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads Order's two events. Derived from decisions.md, "Day 7", points 1 to 4.
 *
 * <p>Both payloads are flat JSON and only {@code order_id} is needed; every
 * other field (including {@code reason}) is ignored. All four
 * {@code OrderCancelled} reasons are handled the same way: the reason says
 * why Order gave up, not what Inventory must do.
 *
 * <p>The listener holds no transaction of its own. {@link ReservationEventService}
 * opens and commits it, and the container moves the offset only after this
 * method RETURNS (ack mode RECORD, auto-commit off), i.e. after the commit.
 * A crash in between means the message arrives again, which is harmless by the
 * status guard. Delivery is at-least-once; nothing here is exactly-once.
 *
 * <p>Failure handling lives in {@link InventoryKafkaConfig}: a
 * {@link MalformedEventException} is skipped, anything else is retried.
 */
@Component
public class OrderEventListeners {

    private final ReservationEventService events;
    private final ObjectMapper json;

    public OrderEventListeners(ReservationEventService events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    @KafkaListener(topics = "order-cancelled", autoStartup = "${kafka.consumers-enabled}")
    public void onOrderCancelled(String message) {
        events.release(orderId(message));
    }

    @KafkaListener(topics = "payment-confirmed", autoStartup = "${kafka.consumers-enabled}")
    public void onPaymentConfirmed(String message) {
        events.confirm(orderId(message));
    }

    /** The {@code order_id} of a flat JSON payload; anything else is malformed. */
    long orderId(String message) {
        if (message == null) {
            throw new MalformedEventException("empty message");
        }
        JsonNode root;
        try {
            root = json.readTree(message);
        } catch (JacksonException e) {
            throw new MalformedEventException("not valid JSON", e);
        }
        JsonNode id = root.path("order_id");
        if (!id.isIntegralNumber() || !id.canConvertToLong()) {
            throw new MalformedEventException("order_id is missing or not a whole number");
        }
        return id.longValue();
    }
}
