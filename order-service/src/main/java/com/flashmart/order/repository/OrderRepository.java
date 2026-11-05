package com.flashmart.order.repository;

import com.flashmart.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Reads, plus one compare-and-set per order transition (architecture.md section 6).
 *
 * <p>There is no {@code save()} path for the CAS-owned columns: they are
 * {@code updatable = false} on {@link Order} (A1). Each method below is one
 * conditional {@code UPDATE} and returns the rows changed. {@code 1} means
 * this caller won; {@code 0} means someone else already moved the row (or it
 * was never in the expected state), and the caller must treat that as a loss,
 * not an error.
 *
 * <p>These methods do not write outbox rows and do not touch the clock. The
 * service layer wraps them in the same local transaction as any outbox row.
 * The one exception to "no clock" is {@link #markPendingPayment}, which must
 * reset {@code expires_at} in the same statement as the status.
 *
 * <p>A native {@code UPDATE} bypasses the persistence context, so an
 * {@link Order} already loaded in the same transaction is stale afterwards.
 * Callers that need the new state must re-read it.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);

    /**
     * Sets the price once. {@code amount} is frozen after that, so a second
     * call matches nothing; and only a {@code CREATED} order may be priced.
     */
    @Modifying
    @Query(value = "UPDATE orders SET amount = :amount "
            + "WHERE id = :id AND status = 'CREATED' AND amount IS NULL", nativeQuery = true)
    int setAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /**
     * {@code CREATED -> RESERVATION_FAILED}. The cause goes into
     * {@code failure_detail} (debug only) in the same statement, so it is
     * written only by the caller that won.
     */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'RESERVATION_FAILED', failure_detail = :failureDetail "
            + "WHERE id = :id AND status = 'CREATED'", nativeQuery = true)
    int markReservationFailed(@Param("id") Long id, @Param("failureDetail") String failureDetail);

    /**
     * {@code CREATED -> PAYMENT_UNAVAILABLE}: no payment could be started
     * (retries exhausted, breaker open, or the gateway refused with a 4xx).
     * The cause goes into {@code failure_detail} in the same statement, so only
     * the winner writes it. Same shape as {@link #markReservationFailed}.
     */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'PAYMENT_UNAVAILABLE', failure_detail = :failureDetail "
            + "WHERE id = :id AND status = 'CREATED'", nativeQuery = true)
    int markPaymentUnavailable(@Param("id") Long id, @Param("failureDetail") String failureDetail);

    /**
     * {@code CREATED -> PENDING_PAYMENT}, the payment started. ONE statement
     * sets the status, the gateway's reference and the new deadline (the
     * payment window, on the database clock), so there is no moment where the
     * sweep could see PENDING_PAYMENT with the old, short CREATED deadline, or a
     * payable order without the ref a webhook must match. V7's CHECK backs the
     * ref, V2's CHECK the amount.
     *
     * <p>Losing (0 rows) means the sweep expired the order, or another request
     * with the same key moved it first.
     */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'PENDING_PAYMENT', payment_ref = :paymentRef, "
            + "expires_at = clock_timestamp() + make_interval(secs => :windowSeconds) "
            + "WHERE id = :id AND status = 'CREATED'", nativeQuery = true)
    int markPendingPayment(@Param("id") Long id, @Param("paymentRef") String paymentRef,
                           @Param("windowSeconds") double windowSeconds);

    /**
     * {@code CREATED -> ORDER_EXPIRED} or {@code PENDING_PAYMENT -> ORDER_EXPIRED},
     * chosen by {@code expectedStatus}. There is deliberately no time condition:
     * the sweep picks due rows in its own SELECT, and this update only decides
     * who wins the row. The {@code IN} list is a second guard so a wrong
     * argument can never expire a {@code PAID} (or any terminal) order.
     */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'ORDER_EXPIRED' "
            + "WHERE id = :id AND status = :expectedStatus "
            + "AND status IN ('CREATED', 'PENDING_PAYMENT')", nativeQuery = true)
    int expire(@Param("id") Long id, @Param("expectedStatus") String expectedStatus);

    /**
     * {@code PENDING_PAYMENT -> PAID}, only for the payment this order holds
     * AND only for the amount it costs (decisions.md, "Day 6", points 2 and 3).
     * A webhook for any other reference, or for a different amount, matches
     * nothing, and the handler then refunds it (one-payment invariant).
     *
     * <p>{@code amount} is compared as a {@code NUMERIC}, never as text, so
     * {@code 499} equals {@code 499.00}.
     */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'PAID' "
            + "WHERE id = :id AND status = 'PENDING_PAYMENT' AND payment_ref = :paymentRef "
            + "AND amount = :amount",
            nativeQuery = true)
    int markPaid(@Param("id") Long id, @Param("paymentRef") String paymentRef,
                 @Param("amount") BigDecimal amount);

    /**
     * {@code PENDING_PAYMENT -> PAYMENT_FAILED}, only for the payment this order
     * holds: a {@code FAILED} for some other payment {@code Y} must not fail an
     * order that is waiting on {@code X} (decisions.md, "Day 6", point 2).
     */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'PAYMENT_FAILED' "
            + "WHERE id = :id AND status = 'PENDING_PAYMENT' AND payment_ref = :paymentRef",
            nativeQuery = true)
    int markPaymentFailed(@Param("id") Long id, @Param("paymentRef") String paymentRef);
}
