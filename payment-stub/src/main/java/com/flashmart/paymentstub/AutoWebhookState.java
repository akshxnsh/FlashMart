package com.flashmart.paymentstub;

import org.springframework.stereotype.Component;

/**
 * Whether a newly created payment also gets a webhook on its own, and when.
 * One immutable pair swapped atomically, like {@link ModeState}.
 *
 * <ul>
 *   <li>{@code BEFORE_REPLY}: the first attempt is sent synchronously before the
 *       initiate {@code 202} is returned, so Order is guaranteed to still be
 *       {@code CREATED} and answers {@code 409}; retries continue after the
 *       reply. The deterministic early-webhook demo.</li>
 *   <li>{@code AFTER_REPLY}: the whole delivery starts on a background thread
 *       {@code webhook.after-reply-delay-ms} after the initiate call. Best
 *       effort: the reply is usually, not provably, out by then.</li>
 * </ul>
 */
@Component
public class AutoWebhookState {

    public enum Status { OFF, SUCCESS, FAILED }

    public enum Timing { BEFORE_REPLY, AFTER_REPLY }

    public record Current(Status status, Timing timing) {
    }

    private volatile Current current = new Current(Status.OFF, Timing.AFTER_REPLY);

    public Current get() {
        return current;
    }

    public void set(Status status, Timing timing) {
        this.current = new Current(status, timing);
    }
}
