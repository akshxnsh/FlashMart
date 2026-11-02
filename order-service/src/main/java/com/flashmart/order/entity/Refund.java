package com.flashmart.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A command outbox whose only consumer is Order itself. The webhook handler
 * writes a row here in the same local transaction as its failed CAS, and a
 * worker drains it — a refund is never an inline call, so the one-payment
 * invariant survives a crash mid-refund.
 */
/*
 * CAS-owned columns (status, attempts, next_retry_at, claimed_at, last_error)
 * are updatable = false: the worker's claim and its claimed_at-fenced finishing
 * write are raw SQL, and a stale entity save must not undo them. order_id,
 * payment_ref and amount are left as they are (not touched by this piece).
 */
@Entity
@Table(name = "refunds", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refunds_payment_ref", columnNames = {"payment_ref"})
})
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /**
     * Unique: refunds are per payment, not per order. An order may hold
     * several captured payments — that is the problem refunds exist to
     * correct — but each is refunded once, and this is what makes a
     * redelivered webhook lose instead of refunding twice.
     */
    @Column(name = "payment_ref", nullable = false)
    private String paymentRef;

    /** The webhook's reported amount: what was captured, not what we think. */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private RefundStatus status;

    @Column(name = "attempts", nullable = false, updatable = false)
    private int attempts;

    /** Set by the claim SQL using the DB clock; compared against the DB's now(). */
    @Column(name = "next_retry_at", updatable = false)
    private Instant nextRetryAt;

    /**
     * Claim lease and fence token in one. The worker's finishing write is
     * conditional on the value it saw, so a worker whose lease expired
     * mid-call cannot overwrite the one that took over.
     */
    @Column(name = "claimed_at", updatable = false)
    private Instant claimedAt;

    @Column(name = "last_error", updatable = false)
    private String lastError;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Owned by {@code trg_refunds_bump_updated_at}; the claim is raw SQL. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
