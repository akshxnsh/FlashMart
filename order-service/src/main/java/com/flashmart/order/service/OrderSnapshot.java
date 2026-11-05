package com.flashmart.order.service;

import com.flashmart.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An order as one native read saw it. {@code beforeDeadline} is computed by the
 * database in the same SELECT ({@code clock_timestamp() < expires_at}), so no
 * pod clock decides whether a retry may resume the loop. {@code paymentRef} is
 * NULL until the payment started; {@code expiresAt} is the live deadline (the
 * CREATED deadline, or the payment window once PENDING_PAYMENT).
 */
public record OrderSnapshot(
        long id,
        long buyerId,
        long flashSaleId,
        OrderStatus status,
        BigDecimal amount,
        boolean beforeDeadline,
        String paymentRef,
        Instant expiresAt
) {
}
