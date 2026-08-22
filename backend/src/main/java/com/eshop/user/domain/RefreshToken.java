package com.eshop.user.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One issued refresh token.
 *
 * <p>Tokens rotate: every use mints a replacement and revokes the presented one. All tokens
 * descended from a single login share a {@code familyId}, which is what makes theft detectable -
 * if a revoked token is presented again, either the legitimate client or an attacker is replaying,
 * and there is no way to tell which. The safe response is to revoke the entire family and force a
 * fresh login.
 *
 * <p>Only the SHA-256 of the token is stored. A database dump therefore does not yield usable
 * sessions. Plain SHA-256 rather than Argon2 is appropriate here because the input is already 256
 * bits of {@link java.security.SecureRandom} output - there is no dictionary to attack.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "user_agent")
    private String userAgent;

    // PostgreSQL `inet`. Bound from a plain String thanks to stringtype=unspecified on the
    // datasource URL; the columnDefinition is what lets schema validation recognise it.
    @Column(name = "ip", columnDefinition = "inet")
    private String ip;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isUsable() {
        return !isRevoked() && !isExpired();
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }
}
