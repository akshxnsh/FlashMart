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
        // refresh_tokens has an FK to users (no cascade), so tokens go first.
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%@" + domain);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%@" + domain);
    }

    /** revoked_at of the row holding this raw token's hash; throws if there is no such row. */
    static java.time.Instant revokedAt(JdbcTemplate jdbc, String rawToken) {
        return jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                (rs, n) -> {
                    java.time.OffsetDateTime t = rs.getObject("revoked_at", java.time.OffsetDateTime.class);
                    return t == null ? null : t.toInstant();
                }, RefreshTokens.hash(rawToken));
    }

    static long liveTokens(JdbcTemplate jdbc, long userId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, userId);
        return n == null ? 0 : n;
    }

    static long allTokens(JdbcTemplate jdbc, long userId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    static long userCount(JdbcTemplate jdbc, String email) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email);
        return n == null ? 0 : n;
    }
}
