package com.flashmart.paymentstub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How the stub delivers webhooks to Order. The retry interval and the give-up
 * window are the stub's ASSUMPTION about how a real gateway behaves; the
 * Razorpay Clone does not exist, so nothing here is verified against one.
 *
 * <p>Order answers {@code 409} to a webhook for a still-CREATED order and relies
 * on the gateway to retry (decisions.md, "Day 6", point 1), so the give-up
 * window must outlast Order's longest CREATED deadline. That deadline lives in
 * Order's config, not here; {@code webhook.order-max-created-deadline-ms} is a
 * copy of it (62.5 s for 17 lines, decisions.md "Day 5", point 6), checked
 * once at startup.
 */
@Component
public class WebhookSettings {

    private static final Logger log = LoggerFactory.getLogger(WebhookSettings.class);

    private final String secret;
    private final String url;
    private final long retryIntervalMs;
    private final long maxDurationMs;
    private final long requestTimeoutMs;
    private final long afterReplyDelayMs;

    public WebhookSettings(@Value("${webhook.secret:}") String secret,
                           @Value("${webhook.url}") String url,
                           @Value("${webhook.retry-interval-ms}") long retryIntervalMs,
                           @Value("${webhook.max-duration-ms}") long maxDurationMs,
                           @Value("${webhook.request-timeout-ms}") long requestTimeoutMs,
                           @Value("${webhook.after-reply-delay-ms}") long afterReplyDelayMs,
                           @Value("${webhook.order-max-created-deadline-ms}") long orderMaxCreatedDeadlineMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("webhook.secret is blank: set PAYMENT_WEBHOOK_SECRET (see .env.example)");
        }
        if (retryIntervalMs <= 0 || maxDurationMs <= 0 || requestTimeoutMs <= 0 || afterReplyDelayMs < 0) {
            throw new IllegalStateException("webhook retry, duration and timeout values must be positive");
        }
        if (maxDurationMs <= orderMaxCreatedDeadlineMs) {
            log.warn("webhook.max-duration-ms ({} ms) does not exceed Order's longest CREATED deadline ({} ms): "
                    + "an early webhook (409) may be given up on before Order can accept it",
                    maxDurationMs, orderMaxCreatedDeadlineMs);
        }
        this.secret = secret;
        this.url = url;
        this.retryIntervalMs = retryIntervalMs;
        this.maxDurationMs = maxDurationMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.afterReplyDelayMs = afterReplyDelayMs;
    }

    public String secret() {
        return secret;
    }

    public String url() {
        return url;
    }

    public long retryIntervalMs() {
        return retryIntervalMs;
    }

    public long maxDurationMs() {
        return maxDurationMs;
    }

    public long requestTimeoutMs() {
        return requestTimeoutMs;
    }

    public long afterReplyDelayMs() {
        return afterReplyDelayMs;
    }
}
