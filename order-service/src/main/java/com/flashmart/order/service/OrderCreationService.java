package com.flashmart.order.service;

import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.InventoryUnavailableException;
import com.flashmart.order.client.inventory.ReserveRequest;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.client.payment.InitiatePaymentResponse;
import com.flashmart.order.client.payment.PaymentCalls;
import com.flashmart.order.client.payment.PaymentRejectedException;
import com.flashmart.order.client.payment.PaymentUnavailableException;
import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.CreateOrderResponse;
import com.flashmart.order.dto.ErrorCode;
import com.flashmart.order.dto.OrderErrorResponse;
import com.flashmart.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code POST /api/orders}. Deliberately NOT transactional: every database
 * step is its own short transaction in {@link OrderCreationTransaction}, so a
 * unique violation can be answered after its transaction has rolled back.
 *
 * <p>Design: decisions.md, "Order creation: retries, conflicts, deadlines and
 * Inventory failures" (insert, conflicts, reserve loop) and "Day 5: payment
 * step, resilience and derived deadlines" (amount, initiate, PENDING_PAYMENT).
 *
 * <p>Flow: insert, reserve loop, amount, {@code requirePayable}, initiate,
 * PENDING_PAYMENT CAS, answer. No database transaction is open across either
 * remote call (Inventory or the payment gateway).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    /** Postgres {@code unique_violation}. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final OrderCreationTransaction transaction;
    private final CreateOrderSettings settings;
    private final InventoryCalls inventory;
    private final PaymentCalls payments;
    private final PaymentSettings paymentSettings;

    /** Shown when the order expired while its payment was being started (Day 6 refunds any payment). */
    static final String EXPIRED_MESSAGE = "Something went wrong. If any money was deducted, it will be refunded.";

    public CreateOrderResult create(long buyerId, String idempotencyKey, CreateOrderRequest request) {
        List<OrderLine> lines = merge(request.items());
        long id;
        try {
            id = transaction.insert(buyerId, request.flashSaleId(), idempotencyKey, lines);
        } catch (DataIntegrityViolationException e) {
            if (!isUniqueViolation(e)) {
                throw e;
            }
            return onUniqueViolation(buyerId, idempotencyKey, request.flashSaleId(), lines);
        }
        return reserveAll(new OrderSnapshot(id, buyerId, request.flashSaleId(), OrderStatus.CREATED, null, true,
                null, null));
    }

    /**
     * The reserve loop: one Inventory call per unpriced item, in product order,
     * each followed by its own short transaction. No transaction is open while
     * Inventory is being called.
     *
     * <p>It runs for a new order, and again for a retry of the same key while
     * the order is {@code CREATED} and before its deadline (resume). Two loops
     * on one order are safe only because every write here either is a CAS or
     * is write-once, and losing is never an error: the loser re-reads and
     * reports what is really there.
     */
    private CreateOrderResult reserveAll(OrderSnapshot order) {
        for (OrderLine line : transaction.unpricedItems(order.id())) {
            ReserveResponse answer;
            try {
                answer = inventory.reserve(new ReserveRequest(
                        order.id(), order.flashSaleId(), line.productId(), line.quantity()));
            } catch (InventoryUnavailableException e) {
                // Timeout, refused, or non-200. Not ORDER_EXPIRED: that is the
                // sweep's label and emails the buyer. Known gap: a timed-out
                // call can still commit in Inventory after this, leaving an
                // orphan reservation (covered only by Day 7's designs).
                return fail(order, "product " + line.productId() + ": " + e.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INVENTORY_UNAVAILABLE, null);
            }
            switch (answer.outcome()) {
                case RESERVED -> {
                    if (transaction.priceItem(order.id(), line.productId(), answer.productName(),
                            answer.salePrice()) == 0) {
                        // Priced by another loop (fine), or the order left CREATED:
                        // then stop calling Inventory for an order that is over.
                        OrderSnapshot now = transaction.read(order.id());
                        if (now.status() != OrderStatus.CREATED) {
                            return currentState(now);
                        }
                    }
                }
                case OUT_OF_STOCK, SALE_NOT_LIVE, ITEM_NOT_IN_SALE -> {
                    return fail(order, "product " + line.productId() + ": " + answer.outcome(),
                            HttpStatus.CONFLICT, ErrorCode.RESERVATION_FAILED, answer.outcome().name());
                }
                case RESERVATION_RELEASED -> {
                    return released(order, line);
                }
            }
        }
        transaction.setAmountFromItems(order.id());
        OrderSnapshot now = transaction.read(order.id());
        if (now.status() == OrderStatus.CREATED && now.amount() != null) {
            return pay(now);
        }
        return currentState(now);
    }

    /**
     * Starts the payment for a priced CREATED order and moves it to
     * PENDING_PAYMENT. The gateway call runs with no transaction open; each
     * write after it is its own short CAS transaction.
     *
     * <p>The key is {@code "order-" + id} on every attempt and every retried or
     * resumed request, so a second initiate for the same order returns the same
     * payment (if the gateway honours it; RZP-1, designed, not verified). Only
     * the request whose CAS wins answers 201; any loser re-reads and reports
     * what is really there.
     */
    private CreateOrderResult pay(OrderSnapshot order) {
        transaction.requirePayable(order.id(), order.amount());
        InitiatePaymentResponse payment;
        try {
            payment = payments.initiate(order.id(), order.amount());
        } catch (PaymentUnavailableException e) {
            // Retries exhausted or breaker open. A timed-out call may still have
            // created a payment; the Day 6 webhook handler refunds it.
            return paymentUnavailable(order, e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCode.PAYMENT_UNAVAILABLE, "payment is unavailable; the order was cancelled");
        } catch (PaymentRejectedException e) {
            // A 4xx is almost always our bug. Still cancel, so the stock is released.
            log.error("payment gateway rejected initiate for order {}: {}", order.id(), e.getMessage());
            return paymentUnavailable(order, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCode.PAYMENT_REJECTED, "the payment could not be started; the order was cancelled");
        }
        if (transaction.markPendingPayment(order.id(), payment.paymentRef(), paymentSettings.paymentWindowMs()) == 1) {
            return new CreateOrderResult(true, body(transaction.read(order.id())));
        }
        return lost(transaction.read(order.id()));
    }

    /**
     * {@code CREATED -> PAYMENT_UNAVAILABLE} with its {@code OrderCancelled}.
     * Only the CAS winner answers with the failure; a loser reports the real state.
     */
    private CreateOrderResult paymentUnavailable(OrderSnapshot order, String detail, HttpStatus status,
                                                 ErrorCode code, String message) {
        if (transaction.failPaymentUnavailable(order.id(), order.buyerId(), detail)) {
            throw new OrderRejectedException(status, new OrderErrorResponse(code, message,
                    order.id(), OrderStatus.PAYMENT_UNAVAILABLE.name(), null, null));
        }
        return lost(transaction.read(order.id()));
    }

    /**
     * A payment-step CAS lost. The sweep expired the order: 409 ORDER_EXPIRED
     * (any payment is refunded by the Day 6 webhook handler). A same-key request
     * moved it to PENDING_PAYMENT: 200 with the winner's body. Anything else:
     * the usual mapping of the stored state.
     */
    private CreateOrderResult lost(OrderSnapshot now) {
        if (now.status() == OrderStatus.ORDER_EXPIRED) {
            throw new OrderRejectedException(HttpStatus.CONFLICT, new OrderErrorResponse(ErrorCode.ORDER_EXPIRED,
                    EXPIRED_MESSAGE, now.id(), now.status().name(), now.amount(), null));
        }
        return currentState(now);
    }

    /**
     * Ends the order as {@code RESERVATION_FAILED} with its {@code OrderCancelled}.
     * Only the CAS winner answers with the failure and its reason; a loser
     * (another loop, or the sweep) re-reads and reports the real state.
     */
    private CreateOrderResult fail(OrderSnapshot order, String detail, HttpStatus status, ErrorCode code,
                                   String reason) {
        if (transaction.fail(order.id(), order.buyerId(), detail)) {
            throw new OrderRejectedException(status, new OrderErrorResponse(code,
                    code == ErrorCode.INVENTORY_UNAVAILABLE
                            ? "inventory is unavailable; the order was cancelled"
                            : "an item could not be reserved; the order was cancelled",
                    order.id(), OrderStatus.RESERVATION_FAILED.name(), null, reason));
        }
        return currentState(transaction.read(order.id()));
    }

    /**
     * Inventory says this order's reservation was released, so something has
     * already decided the order's fate. Order never guesses: no CAS, no event.
     * If Order has a terminal state, report it. If Order still says CREATED,
     * the two services disagree and there is no truthful success answer: log
     * and answer 500. Repairing that is the Day 7 reconciler's job (designed,
     * not built), not this request's.
     */
    private CreateOrderResult released(OrderSnapshot order, OrderLine line) {
        OrderSnapshot now = transaction.read(order.id());
        if (now.status() != OrderStatus.CREATED) {
            return currentState(now);
        }
        log.error("Inventory reports RESERVATION_RELEASED for order {} product {} while the order is still "
                + "CREATED; leaving it as it is for reconciliation", order.id(), line.productId());
        throw new OrderRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, new OrderErrorResponse(
                ErrorCode.INCONSISTENT_STATE, "the order is in an inconsistent state",
                order.id(), now.status().name(), null, null));
    }

    /**
     * Runs after the insert's transaction has rolled back (Postgres aborts it),
     * so every lookup here is a new transaction. Which index fired is never
     * read from the constraint name: an insert can break both indexes at once,
     * and a rename must not change behaviour. The questions are asked in a
     * fixed order instead.
     */
    private CreateOrderResult onUniqueViolation(long buyerId, String idempotencyKey, long flashSaleId,
                                                List<OrderLine> lines) {
        // (a) This key was used before: a replay, if it is the same request.
        Optional<OrderSnapshot> sameKey = transaction.findByKey(buyerId, idempotencyKey);
        if (sameKey.isPresent()) {
            return replay(sameKey.get(), flashSaleId, lines);
        }
        // (b) Another order of this buyer holds the sale.
        Optional<OrderSnapshot> open = transaction.findLiveOrPaid(buyerId, flashSaleId);
        if (open.isPresent()) {
            throw alreadyOpen(open.get());
        }
        // (c) The winner is gone already (for example it failed in between).
        throw new OrderRejectedException(HttpStatus.CONFLICT, OrderErrorResponse.of(ErrorCode.CONFLICT_RETRY,
                "lost a race with another request; retry with the same Idempotency-Key"));
    }

    /**
     * Same key. The request must match what is stored before anything else
     * runs, so a reused key can never read, resume or replay someone else's
     * request.
     */
    private CreateOrderResult replay(OrderSnapshot order, long flashSaleId, List<OrderLine> lines) {
        if (order.flashSaleId() != flashSaleId || !transaction.quantitiesOf(order.id()).equals(asMap(lines))) {
            throw new OrderRejectedException(HttpStatus.CONFLICT, OrderErrorResponse.of(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "this Idempotency-Key was already used for a different order request"));
        }
        if (order.status() == OrderStatus.CREATED && order.beforeDeadline()) {
            return reserveAll(order);
        }
        return currentState(order);
    }

    /**
     * The state as stored, as an answer (never 201). A failed order replays with
     * no reason: the reason was given once, and {@code failure_detail} is
     * debug-only. PENDING_PAYMENT replays the same body the winner got, read
     * from the row. PAYMENT_UNAVAILABLE replays the 503; a new attempt needs a
     * new key.
     */
    CreateOrderResult currentState(OrderSnapshot order) {
        if (order.status() == OrderStatus.RESERVATION_FAILED) {
            throw new OrderRejectedException(HttpStatus.CONFLICT, new OrderErrorResponse(
                    ErrorCode.RESERVATION_FAILED, "this order could not be reserved",
                    order.id(), order.status().name(), null, null));
        }
        if (order.status() == OrderStatus.PAYMENT_UNAVAILABLE) {
            throw new OrderRejectedException(HttpStatus.SERVICE_UNAVAILABLE, new OrderErrorResponse(
                    ErrorCode.PAYMENT_UNAVAILABLE, "payment was unavailable; this order was cancelled",
                    order.id(), order.status().name(), null, null));
        }
        return new CreateOrderResult(false, body(order));
    }

    /** {@code expiresAt} is the payment window's end, so it is shown only while PENDING_PAYMENT. */
    private static CreateOrderResponse body(OrderSnapshot order) {
        return new CreateOrderResponse(order.id(), order.status().name(), order.amount(), order.paymentRef(),
                order.status() == OrderStatus.PENDING_PAYMENT ? order.expiresAt() : null);
    }

    private static OrderRejectedException alreadyOpen(OrderSnapshot open) {
        String message = open.status() == OrderStatus.PAID
                ? "you have already bought from this sale"
                : "you already have an open order for this sale; finish or wait for it";
        return new OrderRejectedException(HttpStatus.CONFLICT, new OrderErrorResponse(
                ErrorCode.ORDER_ALREADY_OPEN, message, open.id(), open.status().name(), open.amount(), null));
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static Map<Long, Integer> asMap(List<OrderLine> lines) {
        Map<Long, Integer> map = new TreeMap<>();
        lines.forEach(line -> map.put(line.productId(), line.quantity()));
        return map;
    }

    /**
     * Merges duplicate product ids by summing their quantities, then checks the
     * cap on the merged quantity and the line count against {@code maxLines}.
     * Sorted by product id, so the reserve loop and the key-reuse comparison
     * do not depend on the order the client listed items in.
     */
    List<OrderLine> merge(List<CreateOrderRequest.Item> items) {
        Map<Long, Integer> merged = new TreeMap<>();
        for (CreateOrderRequest.Item item : items) {
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }
        List<OrderLine> lines = new ArrayList<>(merged.size());
        merged.forEach((productId, quantity) -> {
            if (quantity > CreateOrderRequest.MAX_QUANTITY_PER_ITEM) {
                throw new OrderRejectedException(HttpStatus.BAD_REQUEST, OrderErrorResponse.of(
                        ErrorCode.INVALID_QUANTITY, "product " + productId + " totals " + quantity
                                + " units; at most " + CreateOrderRequest.MAX_QUANTITY_PER_ITEM + " per product"));
            }
            lines.add(new OrderLine(productId, quantity));
        });
        if (lines.size() > settings.maxLines()) {
            throw new OrderRejectedException(HttpStatus.BAD_REQUEST, OrderErrorResponse.of(
                    ErrorCode.TOO_MANY_LINES, lines.size() + " products; at most " + settings.maxLines()));
        }
        return lines;
    }
}
