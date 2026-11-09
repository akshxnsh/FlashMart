package com.flashmart.order.controller;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Only a pool timeout becomes 503; the same Spring types for anything else keep the default 500. */
class PoolTimeoutHandlerTest {

    private final PoolTimeoutHandler handler = new PoolTimeoutHandler();

    @Test
    void aPoolTimeoutAnywhereInTheCauseChainIs503() {
        var ex = new CannotCreateTransactionException("x", new RuntimeException(
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));
        assertEquals(503, handler.poolTimeout(ex).getStatusCode().value());
    }

    @Test
    void anyOtherFailureOfTheSameTypesIsRethrownUnchanged() {
        var ex = new DataAccessResourceFailureException("db down", new SQLException("refused"));
        assertSame(ex, assertThrows(DataAccessResourceFailureException.class, () -> handler.poolTimeout(ex)));
    }
}
