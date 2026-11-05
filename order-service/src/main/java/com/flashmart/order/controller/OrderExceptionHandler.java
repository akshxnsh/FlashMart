package com.flashmart.order.controller;

import com.flashmart.order.dto.ErrorCode;
import com.flashmart.order.dto.OrderErrorResponse;
import com.flashmart.order.service.OrderRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import tools.jackson.core.JacksonException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Maps the create endpoint's failures to {@code { code, message, ... }}.
 * Scoped to {@link OrderController}. Anything not mapped here falls through to
 * Spring's default 500; no code is invented for it.
 */
@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderExceptionHandler {

    private static final String QUANTITY = "quantity";

    @ExceptionHandler(OrderRejectedException.class)
    public ResponseEntity<OrderErrorResponse> rejected(OrderRejectedException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getBody());
    }

    // The body failed Bean Validation (no constrained header involved).
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public OrderErrorResponse invalidBody(MethodArgumentNotValidException ex) {
        return invalid(ex.getBindingResult().getFieldErrors().stream().map(FieldError::getField).toList());
    }

    // Constrained headers make Spring validate the whole method, so header and
    // body errors both arrive here. Body field errors carry their path.
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public OrderErrorResponse invalidParameters(HandlerMethodValidationException ex) {
        List<String> fields = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors errors) {
                errors.getFieldErrors().forEach(e -> fields.add(e.getField()));
            } else {
                fields.add(String.valueOf(result.getMethodParameter().getParameterName()));
            }
        });
        return invalid(fields);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public OrderErrorResponse badHeader(Exception ex) {
        return OrderErrorResponse.of(ErrorCode.INVALID_REQUEST, ex.getMessage());
    }

    // Broken JSON, or a value of the wrong type such as {"quantity": 2.5}
    // (application.yml turns accept-float-as-int off, as Inventory does).
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public OrderErrorResponse unreadableBody(HttpMessageNotReadableException ex) {
        boolean onQuantity = ex.getMostSpecificCause() instanceof JacksonException jackson
                && !jackson.getPath().isEmpty()
                && QUANTITY.equals(jackson.getPath().getLast().getPropertyName());
        return onQuantity
                ? OrderErrorResponse.of(ErrorCode.INVALID_QUANTITY, "quantity must be a whole number from 1 to 5")
                : OrderErrorResponse.of(ErrorCode.INVALID_REQUEST, "request body could not be read");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public OrderErrorResponse unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return OrderErrorResponse.of(ErrorCode.INVALID_REQUEST, "unsupported content type: " + ex.getContentType());
    }

    private static OrderErrorResponse invalid(List<String> fields) {
        List<String> sorted = fields.stream().sorted().toList();
        boolean quantity = sorted.stream().flatMap(f -> Stream.of(f.substring(f.lastIndexOf('.') + 1)))
                .anyMatch(QUANTITY::equals);
        return quantity
                ? OrderErrorResponse.of(ErrorCode.INVALID_QUANTITY, "quantity must be from 1 to 5")
                : OrderErrorResponse.of(ErrorCode.INVALID_REQUEST, "invalid fields: " + sorted);
    }
}
