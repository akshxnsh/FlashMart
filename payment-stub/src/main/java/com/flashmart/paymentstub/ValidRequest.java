package com.flashmart.paymentstub;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * A validated initiate request. The amount is held at scale 2, so "10.5" and
 * "10.50" are the same request (record equality is what "same body" means).
 */
public record ValidRequest(long orderId, BigDecimal amount, String currency) {

    /** Plain decimal, at most 2 places: no sign, no exponent, no thousands separator. */
    private static final Pattern AMOUNT = Pattern.compile("\\d+(\\.\\d{1,2})?");

    /** @throws IllegalArgumentException with a message for the 400 body */
    public static ValidRequest from(InitiateRequest raw) {
        if (raw.orderId() == null || raw.orderId() <= 0) {
            throw new IllegalArgumentException("orderId must be a positive number");
        }
        if (raw.amount() == null || !raw.amount().isString()) {
            throw new IllegalArgumentException("amount must be a decimal string, e.g. \"499.00\"");
        }
        String text = raw.amount().asString();
        if (!AMOUNT.matcher(text).matches()) {
            throw new IllegalArgumentException("amount must be a plain decimal with at most 2 places");
        }
        BigDecimal amount = new BigDecimal(text).setScale(2);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (!"INR".equals(raw.currency())) {
            throw new IllegalArgumentException("currency must be INR");
        }
        return new ValidRequest(raw.orderId(), amount, "INR");
    }
}
