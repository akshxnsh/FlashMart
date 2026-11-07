package com.flashmart.identity.auth;

import com.flashmart.identity.auth.dto.LoginRequest;
import com.flashmart.identity.auth.dto.TokenResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * {@code POST /api/auth/login} (decisions.md, "Day 8", point 6).
 *
 * <p>Unknown email and wrong password are the same {@code 401
 * INVALID_CREDENTIALS}, with the same body. Same body is not enough: if an
 * unknown email answered at once while a known one spent ~bcrypt time
 * checking, the response TIME would tell an attacker which emails have
 * accounts. So an unknown email is still checked, against a dummy hash made
 * at start-up with the same encoder and cost. (Equal work, not a measured
 * equal time.)
 */
@Service
public class LoginService {

    /** bcrypt only reads 72 bytes; see {@link #tooLongForBcrypt}. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwt;
    private final String dummyHash;

    public LoginService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtIssuer jwt) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.dummyHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(random));
    }

    public TokenResponse login(LoginRequest request) {
        StoredUser user = find(request.email());
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches || tooLongForBcrypt(request.password())) {
            throw new InvalidCredentialsException();
        }
        return new TokenResponse(jwt.issueAccessToken(user.id()), jwt.accessTtlSeconds());
    }

    /**
     * Signup never stores a password over 72 bytes, but bcrypt's CHECK ignores
     * every byte past 72, so "stored password + anything" would otherwise
     * match. Rejected after the hash check, so the work done is the same.
     */
    private static boolean tooLongForBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    private StoredUser find(String normalizedEmail) {
        try {
            return jdbc.queryForObject("SELECT id, password_hash FROM users WHERE email = ?",
                    (rs, n) -> new StoredUser(rs.getLong("id"), rs.getString("password_hash")), normalizedEmail);
        } catch (EmptyResultDataAccessException unknown) {
            return null;
        }
    }

    private record StoredUser(long id, String passwordHash) {
    }
}
