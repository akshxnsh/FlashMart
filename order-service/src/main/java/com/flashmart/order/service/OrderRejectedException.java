package com.flashmart.order.service;

import com.flashmart.order.dto.OrderErrorResponse;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A create request answered with a non-2xx: the status and the body to send. */
@Getter
public class OrderRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final OrderErrorResponse body;

    public OrderRejectedException(HttpStatus status, OrderErrorResponse body) {
        super(body.code() + ": " + body.message());
        this.status = status;
        this.body = body;
    }
}
