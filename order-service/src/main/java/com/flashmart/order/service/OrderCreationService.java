package com.flashmart.order.service;

import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.CreateOrderResponse;
import com.flashmart.order.dto.ErrorCode;
import com.flashmart.order.dto.OrderErrorResponse;
import com.flashmart.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code POST /api/orders}. Deliberately NOT transactional: every database
 * step is its own short transaction in {@link OrderCreationTransaction}.
 */
@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private final OrderCreationTransaction transaction;
    private final CreateOrderSettings settings;

    public CreateOrderResult create(long buyerId, String idempotencyKey, CreateOrderRequest request) {
        List<OrderLine> lines = merge(request.items());
        long id = transaction.insert(buyerId, request.flashSaleId(), idempotencyKey, lines);
        return new CreateOrderResult(true, new CreateOrderResponse(id, OrderStatus.CREATED.name(), null));
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
