package com.flashmart.paymentstub;

/** How the stub misbehaves on purpose, so Order's timeouts, retries and breaker have something to hit. */
public enum Mode {
    /** Create and reply at once. */
    HEALTHY,
    /** Wait {@code delayMs}, then create and reply. */
    SLOW_BEFORE,
    /** Create, then wait {@code delayMs} before replying: the caller times out on a payment that exists. */
    SLOW_AFTER,
    /** Reply 500; nothing created. */
    ERROR_5XX,
    /** Reply 400; nothing created. */
    REJECT_4XX
}
