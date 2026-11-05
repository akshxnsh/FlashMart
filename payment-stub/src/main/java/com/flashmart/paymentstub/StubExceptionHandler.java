package com.flashmart.paymentstub;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** An unreadable body (bad JSON, wrong types) is an INVALID_REQUEST, not Spring's default error page. */
@RestControllerAdvice
public class StubExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "the body could not be read"));
    }
}
