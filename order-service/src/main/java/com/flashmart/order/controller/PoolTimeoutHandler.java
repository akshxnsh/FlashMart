package com.flashmart.order.controller;

import com.flashmart.order.dto.ErrorCode;
import com.flashmart.order.dto.OrderErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLTransientConnectionException;

/**
 * A borrow from the Hikari pool that timed out (decisions.md, "Day 9, Part F")
 * answers {@code 503 SERVICE_BUSY} on EVERY Order endpoint, not Spring's default
 * 500. Not scoped to one controller on purpose: the create path and the payment
 * webhook both borrow, and both callers retry a 503 (the buyer with the same
 * key; the payment gateway on any non-2xx).
 *
 * <p>Nothing is changed here: whatever the request already committed stays. An
 * order inserted before the failed borrow is still {@code CREATED}; a same-key
 * retry resumes it before its deadline, and the expiry sweep ends it after.
 *
 * <p>Only a pool timeout is mapped: Hikari reports it as
 * {@link SQLTransientConnectionException} somewhere in the cause chain. Any other
 * failure of the same Spring types is rethrown and keeps the default 500. The
 * body names no internals.
 */
@Slf4j
@RestControllerAdvice
public class PoolTimeoutHandler {

    static final String MESSAGE = "the service is busy; retry with the same Idempotency-Key";

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<OrderErrorResponse> poolTimeout(RuntimeException ex) {
        if (!isPoolTimeout(ex)) {
            throw ex;
        }
        log.warn("no database connection within the pool timeout: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(OrderErrorResponse.of(ErrorCode.SERVICE_BUSY, MESSAGE));
    }

    static boolean isPoolTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }
}
