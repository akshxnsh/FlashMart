package com.flashmart.identity.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signup INSERT, in its own short transaction.
 *
 * <p>A separate bean on purpose: the caller ({@link SignupService}) must catch
 * the unique violation OUTSIDE this transaction. Postgres aborts a
 * transaction after any error, so nothing more can run inside it; the
 * exception has to leave this method, roll the transaction back, and only
 * then be answered.
 */
@Component
@RequiredArgsConstructor
public class UserInsert {

    private final JdbcTemplate jdbc;

    /**
     * Native {@code INSERT ... RETURNING id}: the statement runs at once, so a
     * unique violation is thrown here, not at some later flush.
     * {@code created_at}/{@code updated_at} are left to their database defaults.
     */
    @Transactional
    public long insert(String name, String normalizedEmail, String passwordHash) {
        Long id = jdbc.queryForObject(
                "INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, normalizedEmail, passwordHash);
        if (id == null) {
            throw new IllegalStateException("INSERT ... RETURNING id returned no id");
        }
        return id;
    }
}
