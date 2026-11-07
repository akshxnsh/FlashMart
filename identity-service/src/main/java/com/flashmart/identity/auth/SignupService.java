package com.flashmart.identity.auth;

import com.flashmart.identity.auth.dto.SignupRequest;
import com.flashmart.identity.auth.dto.SignupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

/**
 * {@code POST /api/auth/signup} (decisions.md, "Day 8", point 6).
 *
 * <p>Deliberately NOT transactional. There is no "does this email exist?"
 * check first: two buyers signing up with the same email at the same moment
 * would both see "no" and both insert. {@code uk_users_email} is the only
 * guard that cannot be raced, so the insert is simply tried and its unique
 * violation (SQLState {@code 23505}) becomes {@code 409 EMAIL_TAKEN}.
 *
 * <p>The hash is computed before the transaction opens, so bcrypt's
 * deliberate slowness never holds a database connection.
 *
 * <p>No {@code UserUpserted} outbox row is written: Identity's outbox events
 * are designed only for now. Users created here need the one-off backfill
 * republish (architecture.md §9), like every user that predates the relay.
 */
@Service
@RequiredArgsConstructor
public class SignupService {

    private static final String UNIQUE_VIOLATION = "23505";

    private final UserInsert users;
    private final PasswordEncoder passwordEncoder;

    public SignupResponse signup(SignupRequest request) {
        String hash = passwordEncoder.encode(request.password());
        try {
            long id = users.insert(request.name(), request.email(), hash);
            return new SignupResponse(id, request.name(), request.email());
        } catch (DataIntegrityViolationException e) {
            // Any 23505 on users: the only unique key a fresh insert can hit is
            // uk_users_email (the id comes from the sequence). The constraint
            // name is not parsed, same rule as Order's create.
            if (!isUniqueViolation(e)) {
                throw e;
            }
            throw new EmailTakenException();
        }
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
