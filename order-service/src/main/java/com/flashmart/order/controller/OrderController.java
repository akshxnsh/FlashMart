package com.flashmart.order.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.flashmart.order.auth.GatewayIdentityFilter;
import com.flashmart.order.dto.CreateOrderRequest;
import com.flashmart.order.dto.CreateOrderResponse;
import com.flashmart.order.service.CreateOrderResult;
import com.flashmart.order.service.OrderCreationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Orders", description = "Needs a Gateway-signed X-Buyer-Id.")
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderCreationService orders;

    /**
     * 201 for a new order, 200 for a replay of the same idempotency key. Every
     * non-2xx comes from {@link OrderExceptionHandler}.
     *
     * <p>{@code Idempotency-Key} is capped at 255, the column's width, so an
     * over-long key is a 400 and not a database error.
     */
    @Operation(summary = "Place an order (idempotent on Idempotency-Key).",
            description = "Requires X-Buyer-Id, X-Auth-Timestamp and X-Auth-Signature, signed by the Gateway. "
                    + "Without a valid signature the answer is 401.")
    @PostMapping("/api/orders")
    public ResponseEntity<CreateOrderResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            // Set only by GatewayIdentityFilter after it verified the Gateway's
            // signature over X-Buyer-Id (decisions.md, "Day 8", point 5). A bare
            // X-Buyer-Id from a caller is never read: without a valid signature
            // the request is a 401 before it gets here.
            @RequestAttribute(GatewayIdentityFilter.VERIFIED_BUYER_ID) long buyerId,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderResult result = orders.create(buyerId, idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }
}
