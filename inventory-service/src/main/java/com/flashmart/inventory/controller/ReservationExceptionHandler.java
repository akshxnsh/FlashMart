package com.flashmart.inventory.controller;

import com.flashmart.inventory.dto.reserve.ErrorCode;
import com.flashmart.inventory.dto.reserve.ErrorResponse;
import com.flashmart.inventory.exception.ItemNotFoundException;
import com.flashmart.inventory.exception.QuantityMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

import java.util.List;

/**
 * Maps reserve's failures to its error contract. Scoped to
 * {@link ReservationController} so the public controllers, when they exist, do
 * not inherit reserve-only codes. Anything not mapped here falls through to
 * Spring's default 500; no code is invented for it.
 */
@RestControllerAdvice(assignableTypes = ReservationController.class)
public class ReservationExceptionHandler {

    private static final String QUANTITY = "quantity";
    private static final String QUANTITY_MESSAGE = "quantity must be a positive integer";

    // Quantity wins when several fields are bad: validating quantity is step 1
    // of reserve's evaluation order.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidField(MethodArgumentNotValidException ex) {
        List<String> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .sorted()
                .toList();
        return fields.contains(QUANTITY)
                ? new ErrorResponse(ErrorCode.INVALID_QUANTITY, QUANTITY_MESSAGE)
                : new ErrorResponse(ErrorCode.INVALID_REQUEST, "invalid fields: " + fields);
    }

    // A body Jackson cannot bind at all: broken JSON, or a value of the wrong
    // type such as {"quantity": 2.5}, rejected because application.yml turns
    // accept-float-as-int off.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unreadableBody(HttpMessageNotReadableException ex) {
        return failedOnQuantity(ex)
                ? new ErrorResponse(ErrorCode.INVALID_QUANTITY, QUANTITY_MESSAGE)
                : new ErrorResponse(ErrorCode.INVALID_REQUEST, "request body could not be read");
    }

    @ExceptionHandler(ItemNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse itemNotFound(ItemNotFoundException ex) {
        return new ErrorResponse(ErrorCode.ITEM_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(QuantityMismatchException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse quantityMismatch(QuantityMismatchException ex) {
        return new ErrorResponse(ErrorCode.QUANTITY_MISMATCH, ex.getMessage());
    }

    // Jackson records the JSON path of the value it failed on; the last
    // element names the field.
    private static boolean failedOnQuantity(HttpMessageNotReadableException ex) {
        if (!(ex.getMostSpecificCause() instanceof JacksonException jackson)) {
            return false;
        }
        List<JacksonException.Reference> path = jackson.getPath();
        return !path.isEmpty() && QUANTITY.equals(path.getLast().getPropertyName());
    }
}
