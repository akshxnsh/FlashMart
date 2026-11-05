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
 * Not built yet: {@code CREATED -> PENDING_PAYMENT}, which resets
 * {@code expires_at} to the payment window (payment phase).
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

    @Modifying
    @Query(value = "UPDATE orders SET status = 'PAYMENT_UNAVAILABLE' "
            + "WHERE id = :id AND status = 'CREATED'", nativeQuery = true)
    int markPaymentUnavailable(@Param("id") Long id);

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
     * {@code PENDING_PAYMENT -> PAID}, only for the payment this order holds.
     * A webhook for any other reference matches nothing, and the handler then
     * refunds it (one-payment invariant).
     */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'PAID' "
            + "WHERE id = :id AND status = 'PENDING_PAYMENT' AND payment_ref = :paymentRef",
            nativeQuery = true)
    int markPaid(@Param("id") Long id, @Param("paymentRef") String paymentRef);

    @Modifying
    @Query(value = "UPDATE orders SET status = 'PAYMENT_FAILED' "
            + "WHERE id = :id AND status = 'PENDING_PAYMENT'", nativeQuery = true)
    int markPaymentFailed(@Param("id") Long id);
}
