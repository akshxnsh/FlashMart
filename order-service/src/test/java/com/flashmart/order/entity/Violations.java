package com.flashmart.order.entity;

import jakarta.persistence.PersistenceException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Asserts WHICH constraint fired, not just that something did. A bare
 * {@code assertThrows(PersistenceException.class, ...)} passes when a
 * different guard rejects the row first, so the test it sits in can go on
 * passing after the guard it names has been removed.
 */
final class Violations {

    private Violations() {
    }

    /** A unique or check violation: Hibernate reads the constraint name from Postgres. */
    static void assertConstraint(String constraintName, Executable action) {
        PersistenceException thrown = assertThrows(PersistenceException.class, action);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException violation) {
                String actual = violation.getConstraintName();
                if (constraintName.equals(actual)) {
                    return;
                }
                fail("expected constraint " + constraintName + " but " + actual + " fired: "
                        + rootMessage(thrown));
            }
        }
        // Not reachable as a ConstraintViolationException: fall back to Postgres's own message.
        if (rootMessage(thrown).contains("\"" + constraintName + "\"")) {
            return;
        }
        fail("expected constraint " + constraintName + " but got: " + rootMessage(thrown));
    }

    /** A NOT NULL violation has no constraint name, so the column is named in the message. */
    static void assertNotNull(String column, Executable action) {
        PersistenceException thrown = assertThrows(PersistenceException.class, action);
        String message = rootMessage(thrown);
        if (!message.contains("null value in column \"" + column + "\"")) {
            fail("expected a NOT NULL violation on " + column + " but got: " + message);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
