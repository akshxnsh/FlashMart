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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The row every race resolves on. Order Service is the sole authority on an
 * order's fate, and every write to {@code status} is a compare-and-set, so the
 * request thread, the expiry sweep, the webhook handler and the
 * circuit-breaker path can all contend without coordinating.
 *
 * <p>Note what is absent: there is no {@code version} column. The CAS here is
 * value-based (<em>status is still what I read</em>), not version-based, so
 * optimistic locking would add a second, weaker guard that fails after any
 * concurrent write rather than only a conflicting one.
 *
 * <p>The partial unique index on {@code payment_ref} is deliberately not
 * declared in {@link Table}: {@code @UniqueConstraint} cannot express a
 * {@code WHERE} clause, so declaring it would describe a total constraint the
 * database does not have. It lives in V2 only.
 */
/*
 * CAS-owned columns (status, amount, payment_ref, expires_at,
 * payment_reconciled_at) are updatable = false: they are written by INSERT and
 * afterwards only by raw conditional SQL. Otherwise a stale managed entity
 * flushed for an unrelated field would write every column and overwrite a
 * winning CAS (e.g. the sweep's ORDER_EXPIRED). Setters on them are silently
 * ignored after insert; OrderCasOwnedColumnsTest guards that.
 * buyer_id, flash_sale_id and idempotency_key are set once at insert and never
 * change, so they are updatable = false too; only failure_detail can still be
 * written by an entity save.
 */
@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(name = "uk_orders_buyer_id_idempotency_key", columnNames = {"buyer_id", "idempotency_key"})
})
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identity owns this row, so it is a plain id with no foreign key. */
    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;

    /** Inventory owns this row. Order-level: one order is one brand's sale. */
    @Column(name = "flash_sale_id", nullable = false, updatable = false)
    private Long flashSaleId;

    /**
     * {@code SUM(order_items.subtotal)}, frozen once set — the price the buyer
     * agreed to. Null until the last item is priced, which is why V2 guards it
     * conditionally on status rather than with a {@code NOT NULL}.
     */
    @Column(name = "amount", precision = 14, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private OrderStatus status;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /**
     * What the one-payment CAS matches on. <strong>Not</strong> how a webhook
     * finds its order — that is the order id, because this is null in exactly
     * the crash window a webhook is most likely to arrive in.
     */
    @Column(name = "payment_ref", updatable = false)
    private String paymentRef;

    /**
     * Settable from Java, unlike the other compared times, because the column
     * is {@code NOT NULL} with no {@code DEFAULT} — a default would hard-code
     * a deadline, and the deadline values are still open.
     *
     * <p>That makes this the one place a pod clock could leak in. Production
     * writes it as {@code now() + interval} in SQL, with Java supplying only
     * the duration; this mapping exists so the row can be inserted at all.
     */
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Debug only. Never read to drive state. */
    @Column(name = "failure_detail")
    private String failureDetail;

    /** Written by the reconciliation sweep's own SQL, using the DB clock. */
    @Column(name = "payment_reconciled_at", updatable = false)
    private Instant paymentReconciledAt;

    /**
     * {@code insertable = false} keeps the column out of the INSERT so the
     * {@code DEFAULT} applies — Hibernate would otherwise send an explicit
     * NULL, which beats a DEFAULT. {@code @Generated} is the other half:
     * without it Hibernate never reads the value back, so the field stays null
     * on the instance that was just persisted.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Owned by {@code trg_orders_bump_updated_at}, never by Hibernate: the CAS
     * writes are raw SQL that {@code @UpdateTimestamp} would never see, so a
     * Java-stamped column would silently stop moving on every status change.
     * {@code @Generated} on both events is what makes Hibernate re-read the
     * trigger's value instead of caching a stale one.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;
}
