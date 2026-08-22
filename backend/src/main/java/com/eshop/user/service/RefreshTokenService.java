package com.eshop.user.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.util.Hashes;
import com.eshop.config.AppProperties;
import com.eshop.user.domain.RefreshToken;
import com.eshop.user.domain.User;
import com.eshop.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues, rotates, and revokes refresh tokens.
 *
 * <h2>Why rotation</h2>
 * A long-lived refresh token that never changes is a bearer credential with a month of validity: if
 * it leaks, the attacker keeps access until it expires and nothing distinguishes them from the real
 * user. Rotating on every use means a stolen token is only good until the legitimate client next
 * refreshes.
 *
 * <h2>Why reuse detection</h2>
 * Rotation alone does not tell you that theft happened — it just shortens the window. But once a
 * token has been rotated, a second presentation of that same token can only come from a copy. There
 * is no way to know whether the copy is the thief or the victim, so the safe response is to revoke
 * the whole family: both parties are logged out, and the real user re-authenticates with a password
 * the attacker does not have.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokens;
    private final AppProperties properties;

    /**
     * Mints the first token of a new family, at login.
     *
     * @return the raw token; only its SHA-256 is persisted, so this is the last chance to read it
     */
    @Transactional
    public String issue(User user, String userAgent, String ip) {
        return persist(user, UUID.randomUUID(), userAgent, ip);
    }

    /**
     * Validates the presented token and swaps it for a successor.
     *
     * @throws BusinessException {@code TOKEN_REUSE_DETECTED} if the token was already rotated,
     *                           {@code TOKEN_EXPIRED} if it aged out, {@code TOKEN_INVALID} otherwise
     */
    @Transactional
    public Rotation rotate(String rawToken, String userAgent, String ip) {
        RefreshToken existing = refreshTokens.findByTokenHash(Hashes.sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID,
                        "Refresh token not recognised"));

        if (existing.isRevoked()) {
            // Someone is replaying a token that was already exchanged. Burn the family.
            int revoked = refreshTokens.revokeFamily(existing.getFamilyId(), Instant.now());
            log.warn("refresh_token_reuse_detected user={} family={} revoked={} ip={}",
                    existing.getUser().getId(), existing.getFamilyId(), revoked, ip);
            throw new BusinessException(ErrorCode.TOKEN_REUSE_DETECTED,
                    "Refresh token replay detected; family revoked");
        }

        if (existing.isExpired()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Refresh token expired");
        }

        User user = existing.getUser();
        if (!user.isActive()) {
            refreshTokens.revokeAllForUser(user.getId(), Instant.now());
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED, "Account is not active");
        }

        String replacement = persist(user, existing.getFamilyId(), userAgent, ip);

        existing.revoke();
        refreshTokens.findByTokenHash(Hashes.sha256Hex(replacement))
                .ifPresent(successor -> existing.setReplacedById(successor.getId()));
        refreshTokens.save(existing);

        return new Rotation(user, replacement);
    }

    /** Ends one session. Unknown or already-revoked tokens are a no-op — logout must not fail. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(Hashes.sha256Hex(rawToken)).ifPresent(token -> {
            token.revoke();
            refreshTokens.save(token);
        });
    }

    /** Ends every session for a user: password change, suspension, or an explicit "log out all". */
    @Transactional
    public int revokeAllForUser(UUID userId) {
        int revoked = refreshTokens.revokeAllForUser(userId, Instant.now());
        if (revoked > 0) {
            log.info("refresh_tokens_revoked user={} count={}", userId, revoked);
        }
        return revoked;
    }

    @Transactional(readOnly = true)
    public List<RefreshToken> activeSessions(UUID userId) {
        return refreshTokens.findActiveSessions(userId, Instant.now());
    }

    private String persist(User user, UUID familyId, String userAgent, String ip) {
        String rawToken = Hashes.randomToken();

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(Hashes.sha256Hex(rawToken));
        token.setFamilyId(familyId);
        token.setExpiresAt(Instant.now().plusSeconds(
                properties.getJwt().getRefreshTokenTtlDays() * 86_400L));
        token.setUserAgent(truncate(userAgent, 512));
        token.setIp(ip);

        refreshTokens.save(token);
        return rawToken;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Result of a successful rotation: the owner, and the replacement token to hand back. */
    public record Rotation(User user, String refreshToken) {
    }
}
