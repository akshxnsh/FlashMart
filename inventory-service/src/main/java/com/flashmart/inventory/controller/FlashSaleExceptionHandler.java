package com.flashmart.inventory.controller;

import com.flashmart.inventory.dto.reserve.ErrorCode;
import com.flashmart.inventory.dto.reserve.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Browse 400s as {@code { code, message }}, scoped to {@link FlashSaleController}. */
@RestControllerAdvice(assignableTypes = FlashSaleController.class)
public class FlashSaleExceptionHandler {

    // page=abc, page=1.5, page=99999999999 (does not fit an int)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse notANumber(MethodArgumentTypeMismatchException ex) {
        return new ErrorResponse(ErrorCode.INVALID_REQUEST, "page must be a whole number, 0 or more");
    }

    // page=-1
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse negative(HandlerMethodValidationException ex) {
        return new ErrorResponse(ErrorCode.INVALID_REQUEST, "page must be a whole number, 0 or more");
    }
}
