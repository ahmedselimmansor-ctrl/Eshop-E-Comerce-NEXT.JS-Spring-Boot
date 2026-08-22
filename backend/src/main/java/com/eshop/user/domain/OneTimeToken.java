package com.eshop.user.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Single-use token for email verification, password reset, and phone OTP.
 *
 * <p>Stored hashed, consumed exactly once, and attempt-counted so a six-digit OTP cannot be
 * brute-forced — after a handful of wrong guesses the token is burned rather than merely rejected.
 */
@Entity
@Table(name = "one_time_tokens")
@Getter
@Setter
@NoArgsConstructor
public class OneTimeToken extends BaseEntity {

    public static final int MAX_ATTEMPTS = 5;

    public enum Type {
        EMAIL_VERIFICATION,
        PASSWORD_RESET,
        PHONE_OTP
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private Type type;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Short numeric code for SMS delivery; {@code null} for link-based tokens. */
    @Column(name = "code", length = 10)
    private String code;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "attempts", nullable = false)
    private short attempts = 0;

    public boolean isUsable() {
        return consumedAt == null
                && expiresAt.isAfter(Instant.now())
                && attempts < MAX_ATTEMPTS;
    }

    public void consume() {
        consumedAt = Instant.now();
    }

    public void recordFailedAttempt() {
        attempts = (short) (attempts + 1);
        if (attempts >= MAX_ATTEMPTS) {
            // Burn the token rather than leave it guessable for the rest of its lifetime.
            consumedAt = Instant.now();
        }
    }
}
