package com.flashmart.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

/**
 * A buyer's refresh token, stored as a hash (V8; decisions.md, "Day 8").
 *
 * <p>Read-only from Java, the same idea as Order's A1 rule: rows are inserted
 * by native SQL (so {@code expires_at} uses the database clock), and
 * {@code revoked_at} is written only by the repository's compare-and-set
 * {@code UPDATE}s. Every column is {@code updatable = false}, and the
 * repository offers no {@code save}, so a stale entity can never undo a
 * revocation.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** NULL while live. Written only by the CAS methods in {@code RefreshTokenRepository}. */
    @Column(name = "revoked_at", insertable = false, updatable = false)
    private Instant revokedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
