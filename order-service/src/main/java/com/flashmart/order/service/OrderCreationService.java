package com.flashmart.order.service;

import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.CreateOrderResponse;
import com.flashmart.order.dto.ErrorCode;
import com.flashmart.order.dto.OrderErrorResponse;
import com.flashmart.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
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
 * Inventory failures".
 */
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    /** Postgres {@code unique_violation}. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final OrderCreationTransaction transaction;
    private final CreateOrderSettings settings;

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
        return new CreateOrderResult(true, new CreateOrderResponse(id, OrderStatus.CREATED.name(), null));
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
        return currentState(order);
    }

    /**
     * The state as stored, as an answer. A failed order replays with no reason:
     * the reason was given once, and {@code failure_detail} is debug-only.
     */
    CreateOrderResult currentState(OrderSnapshot order) {
        if (order.status() == OrderStatus.RESERVATION_FAILED) {
            throw new OrderRejectedException(HttpStatus.CONFLICT, new OrderErrorResponse(
                    ErrorCode.RESERVATION_FAILED, "this order could not be reserved",
                    order.id(), order.status().name(), null, null));
        }
        return new CreateOrderResult(false,
                new CreateOrderResponse(order.id(), order.status().name(), order.amount()));
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
