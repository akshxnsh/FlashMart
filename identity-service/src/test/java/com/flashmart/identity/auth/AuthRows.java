package com.flashmart.identity.auth;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cleanup for auth tests that commit real rows (the service runs its own
 * transactions, so a rolled-back test transaction cannot hold them). Each test
 * class owns one email domain and deletes only that domain.
 */
final class AuthRows {

    private AuthRows() {
    }

    static void deleteUsersOfDomain(JdbcTemplate jdbc, String domain) {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%@" + domain);
    }

    static long userCount(JdbcTemplate jdbc, String email) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email);
        return n == null ? 0 : n;
    }
}
