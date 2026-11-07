package com.flashmart.identity.repository;

import com.flashmart.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Every write to {@code refresh_tokens}. Extends {@link Repository}, not
 * {@code JpaRepository}: there is no {@code save}, so the only way to change
 * {@code revoked_at} is one of the compare-and-set statements below, each of
 * which reports whether it won. All times come from the database clock
 * ({@code clock_timestamp()}), the same clock that stamped {@code expires_at}.
 *
 * <p>Callers must be inside a transaction.
 */
public interface RefreshTokenRepository extends Repository<RefreshToken, Long> {

    /** Stores a new token's hash, expiring {@code ttlSeconds} from now on the database clock. */
    @Modifying
    @Query(value = "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) "
            + "VALUES (:userId, :tokenHash, clock_timestamp() + make_interval(secs => :ttlSeconds))",
            nativeQuery = true)
    int insert(@Param("userId") long userId, @Param("tokenHash") String tokenHash,
               @Param("ttlSeconds") long ttlSeconds);

    /**
     * The rotation CAS: revokes the token only if it is still live and not
     * expired, and returns its owner. Empty = this call did not win (the token
     * is unknown, expired, or already revoked; possibly by a concurrent call
     * that got the row first, in which case this statement waited for that
     * call's transaction and then matched nothing).
     */
    @Query(value = "UPDATE refresh_tokens SET revoked_at = clock_timestamp() "
            + "WHERE token_hash = :tokenHash AND revoked_at IS NULL AND expires_at > clock_timestamp() "
            + "RETURNING user_id",
            nativeQuery = true)
    List<Long> revokeIfLive(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Reuse response: revokes every live token of the user. Uses idx_refresh_tokens_user_active. */
    @Modifying
    @Query(value = "UPDATE refresh_tokens SET revoked_at = clock_timestamp() "
            + "WHERE user_id = :userId AND revoked_at IS NULL",
            nativeQuery = true)
    int revokeAllLive(@Param("userId") long userId);

    /** Logout: revokes this one token if it is not revoked yet. No expiry condition: revoking an expired token is harmless. */
    @Modifying
    @Query(value = "UPDATE refresh_tokens SET revoked_at = clock_timestamp() "
            + "WHERE token_hash = :tokenHash AND revoked_at IS NULL",
            nativeQuery = true)
    int revokeOne(@Param("tokenHash") String tokenHash);
}
