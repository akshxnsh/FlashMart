package com.flashmart.identity.auth;

import com.flashmart.identity.entity.RefreshToken;
import com.flashmart.identity.repository.RefreshTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Every refresh-token transaction (decisions.md, "Day 8", points 3 and 4).
 * Each method is one short transaction and RETURNS its outcome; none throws to
 * report a business result. That matters for reuse: the revoke-all must
 * commit, and throwing inside this transaction would roll it back. The caller
 * ({@link TokenService}) turns the outcome into a 401 after the commit.
 */
@Slf4j
@Component
public class TokenTransactions {

    private final RefreshTokenRepository tokens;
    private final RefreshTokens refreshTokens;
    private final JwtIssuer jwt;
    private final long refreshTtlSeconds;

    public TokenTransactions(RefreshTokenRepository tokens, RefreshTokens refreshTokens, JwtIssuer jwt,
                             @Value("${identity.jwt.refresh-ttl}") Duration refreshTtl) {
        if (refreshTtl == null || refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalStateException("identity.jwt.refresh-ttl must be positive");
        }
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
        this.refreshTtlSeconds = refreshTtl.toSeconds();
    }

    /** A new access token plus a new refresh token (raw, returned once). */
    public record IssuedTokens(String accessToken, String refreshToken) {
    }

    public sealed interface RotateOutcome {
        /** This call won the CAS: the old token is revoked and a new pair was issued, all in one commit. */
        record Rotated(IssuedTokens tokens) implements RotateOutcome {
        }

        /** Unknown or merely expired: nothing was changed. */
        record Invalid() implements RotateOutcome {
        }

        /** An already-revoked token was presented: every live token of its owner was revoked, and that has committed. */
        record Reused(long userId, int revoked) implements RotateOutcome {
        }
    }

    /** Login: store a new refresh token for an already-authenticated user. */
    @Transactional
    public IssuedTokens issue(long userId) {
        return newPair(userId);
    }

    /**
     * Refresh. The CAS decides who used this token first; only the winner
     * gets a new pair. The new token is inserted in the SAME transaction as
     * the CAS, so if the insert fails the CAS rolls back with it and the old
     * token is still usable: a failure never burns a buyer's session.
     */
    @Transactional
    public RotateOutcome rotate(String presentedToken) {
        String hash = RefreshTokens.hash(presentedToken);
        List<Long> owner = tokens.revokeIfLive(hash);
        if (owner.size() > 1) {
            throw new IllegalStateException("token_hash is UNIQUE, but the CAS matched " + owner.size() + " rows");
        }
        if (owner.size() == 1) {
            return new RotateOutcome.Rotated(newPair(owner.getFirst()));
        }
        Optional<RefreshToken> row = tokens.findByTokenHash(hash);
        if (row.isPresent() && row.get().getRevokedAt() != null) {
            long userId = row.get().getUserId();
            int revoked = tokens.revokeAllLive(userId);
            log.warn("refresh token reuse for user {}: revoked {} live refresh token(s)", userId, revoked);
            return new RotateOutcome.Reused(userId, revoked);
        }
        return new RotateOutcome.Invalid();
    }

    /**
     * Logout: revoke this token if it is live. Unknown or already revoked is a
     * quiet no-op, and logout never triggers the reuse response.
     */
    @Transactional
    public void logout(String presentedToken) {
        tokens.revokeOne(RefreshTokens.hash(presentedToken));
    }

    private IssuedTokens newPair(long userId) {
        String raw = refreshTokens.newToken();
        tokens.insert(userId, RefreshTokens.hash(raw), refreshTtlSeconds);
        return new IssuedTokens(jwt.issueAccessToken(userId), raw);
    }
}
