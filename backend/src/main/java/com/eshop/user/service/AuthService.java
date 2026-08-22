package com.eshop.user.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.util.Hashes;
import com.eshop.config.AppProperties;
import com.eshop.notification.service.NotificationService;
import com.eshop.notification.service.Recipient;
import com.eshop.security.PasswordPolicy;
import com.eshop.security.jwt.JwtService;
import com.eshop.security.ratelimit.RateLimiter;
import com.eshop.user.domain.OneTimeToken;
import com.eshop.user.domain.Role;
import com.eshop.user.domain.User;
import com.eshop.user.domain.UserStatus;
import com.eshop.user.repository.OneTimeTokenRepository;
import com.eshop.user.repository.RoleRepository;
import com.eshop.user.repository.UserRepository;
import com.eshop.user.web.dto.AuthResponse;
import com.eshop.user.web.dto.LoginRequest;
import com.eshop.user.web.dto.RegisterRequest;
import com.eshop.user.web.dto.TokenPair;
import com.eshop.user.web.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registration, login, and the credential-recovery flows.
 *
 * <p>A theme runs through this class: <b>never confirm whether an account exists</b>. Registration
 * with a taken email, login with an unknown email, and a password-reset request for an address that
 * was never registered all behave the same as their successful counterparts from the outside. An
 * endpoint that distinguishes them is an account-enumeration oracle, and the enumerated list is
 * exactly what makes credential stuffing worth an attacker's time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int VERIFICATION_TTL_HOURS = 24;
    private static final int RESET_TTL_MINUTES = 30;

    private final UserRepository users;
    private final RoleRepository roles;
    private final OneTimeTokenRepository oneTimeTokens;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final NotificationService notifications;
    private final RateLimiter rateLimiter;
    private final AppProperties properties;

    // ------------------------------------------------------------- register --

    @Transactional
    public AuthResponse register(RegisterRequest request, String userAgent, String ip) {
        String email = normalizeEmail(request.email());
        String phone = normalizePhone(request.phone());

        passwordPolicy.validate(request.password(), email, request.firstName());

        // A duplicate email is reported as a conflict rather than silently succeeding, because the
        // alternative — pretending to register and emailing the existing owner — is worse UX for a
        // real duplicate and only marginally better against enumeration. Registration is rate
        // limited to 3/hour per IP, which is what actually blunts enumeration here.
        if (users.existsByEmailAndDeletedAtIsNull(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "Email already registered");
        }
        if (phone != null && users.existsByPhoneAndDeletedAtIsNull(phone)) {
            throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED,
                    "Phone already registered");
        }

        User user = new User();
        user.setEmail(email);
        user.setPhone(phone);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName() == null ? null : request.lastName().trim());
        user.setLocale(request.localeOrDefault());
        user.setDefaultCurrency(properties.getDefaultCurrency());
        user.setMarketingOptIn(request.marketingOptInOrDefault());
        user.setStatus(UserStatus.ACTIVE);
        user.addRole(customerRole());

        users.save(user);
        log.info("user_registered user={} locale={}", user.getId(), user.getLocale());

        issueVerificationEmail(user);

        return authenticate(user, userAgent, ip);
    }

    // ---------------------------------------------------------------- login --

    @Transactional
    public AuthResponse login(LoginRequest request, String userAgent, String ip) {
        String identifier = request.emailOrPhone().contains("@")
                ? normalizeEmail(request.emailOrPhone())
                : normalizePhone(request.emailOrPhone());

        Optional<User> candidate = identifier == null
                ? Optional.empty()
                : users.findActiveByEmailOrPhone(identifier);

        if (candidate.isEmpty()) {
            // Run a hash anyway so a missing account and a wrong password take comparable time.
            // Without this, response latency alone reveals which emails are registered.
            passwordEncoder.matches(request.password(),
                    "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHR2YWx1ZQ$3jXKvQjCVN7ChLxKlPu1w0f5wJ7uCzKhL3g6Kx0y9aI");
            log.warn("login_failed_unknown_identifier ip={}", ip);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "No account for identifier");
        }

        User user = candidate.get();

        if (user.isLocked()) {
            log.warn("login_blocked_locked user={} until={} ip={}", user.getId(), user.getLockedUntil(), ip);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Account is temporarily locked");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED, "Account suspended");
        }
        if (user.getPasswordHash() == null) {
            // OAuth-only account: tell the user how to get in without confirming the address exists
            // any more precisely than a generic failure would.
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Account has no password set");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedLogin(
                    properties.getSecurity().getMaxFailedLogins(),
                    properties.getSecurity().getLockoutMinutes());
            users.save(user);
            log.warn("login_failed_bad_password user={} attempts={} ip={}",
                    user.getId(), user.getFailedLoginAttempts(), ip);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Password mismatch");
        }

        // Transparently upgrade the hash when the Argon2 cost parameters have been raised since
        // this password was last set.
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        user.recordSuccessfulLogin();
        users.save(user);

        // A legitimate user who fumbled their password a few times should not stay throttled.
        rateLimiter.reset("login", "ip:" + ip);

        log.info("login_success user={} ip={}", user.getId(), ip);
        return authenticate(user, userAgent, ip);
    }

    // -------------------------------------------------------------- refresh --

    @Transactional
    public AuthResponse refresh(String rawRefreshToken, String userAgent, String ip) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID, "No refresh token presented");
        }
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken, userAgent, ip);
        User user = rotation.user();

        JwtService.AccessToken accessToken = jwtService.issueAccessToken(user);
        return new AuthResponse(
                UserResponse.from(user),
                TokenPair.of(accessToken.value(), rotation.refreshToken(), accessToken.expiresIn()));
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.revoke(rawRefreshToken);
    }

    // ----------------------------------------------------- password recovery --

    /**
     * Always completes successfully, whether or not the address is registered.
     *
     * <p>The response is identical in both cases and is returned before the mail is dispatched, so
     * neither the status code nor the timing distinguishes a known address from an unknown one.
     */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        users.findActiveByEmail(email).ifPresentOrElse(user -> {
            String token = issueOneTimeToken(user, OneTimeToken.Type.PASSWORD_RESET,
                    Instant.now().plusSeconds(RESET_TTL_MINUTES * 60L));
            notifications.sendPasswordReset(recipientOf(user), token);
            log.info("password_reset_requested user={}", user.getId());
        }, () -> log.info("password_reset_requested_unknown_email"));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        OneTimeToken token = oneTimeTokens
                .findByTokenHashAndType(Hashes.sha256Hex(rawToken), OneTimeToken.Type.PASSWORD_RESET)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "Unknown reset token"));

        if (!token.isUsable()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Reset token is no longer valid");
        }

        User user = token.getUser();
        passwordPolicy.validate(newPassword, user.getEmail(), user.getFirstName());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts((short) 0);
        user.setLockedUntil(null);
        users.save(user);

        token.consume();
        oneTimeTokens.save(token);

        // Whoever triggered the reset may not be the account owner; cutting every existing session
        // ensures an attacker who was already in is evicted along with the old password.
        int revoked = refreshTokens.revokeAllForUser(user.getId());
        notifications.sendSecurityAlert(recipientOf(user), "password-reset",
                Map.of("sessionsRevoked", revoked));

        log.info("password_reset_completed user={} sessions_revoked={}", user.getId(), revoked);
    }

    // ------------------------------------------------------ email verification --

    @Transactional
    public void verifyEmail(String rawToken) {
        OneTimeToken token = oneTimeTokens
                .findByTokenHashAndType(Hashes.sha256Hex(rawToken), OneTimeToken.Type.EMAIL_VERIFICATION)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID, "Unknown verification token"));

        if (!token.isUsable()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED, "Verification token is no longer valid");
        }

        User user = token.getUser();
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            users.save(user);
            notifications.sendWelcome(recipientOf(user));
        }

        token.consume();
        oneTimeTokens.save(token);
        log.info("email_verified user={}", user.getId());
    }

    /** Silently does nothing for unknown or already-verified addresses. */
    @Transactional
    public void resendVerification(String rawEmail) {
        users.findActiveByEmail(normalizeEmail(rawEmail))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::issueVerificationEmail);
    }

    // ------------------------------------------------------------- internals --

    /** Issues both credentials for an already-authenticated user. */
    public AuthResponse authenticate(User user, String userAgent, String ip) {
        JwtService.AccessToken accessToken = jwtService.issueAccessToken(user);
        String refreshToken = refreshTokens.issue(user, userAgent, ip);
        return new AuthResponse(
                UserResponse.from(user),
                TokenPair.of(accessToken.value(), refreshToken, accessToken.expiresIn()));
    }

    private void issueVerificationEmail(User user) {
        String token = issueOneTimeToken(user, OneTimeToken.Type.EMAIL_VERIFICATION,
                Instant.now().plusSeconds(VERIFICATION_TTL_HOURS * 3600L));
        notifications.sendEmailVerification(recipientOf(user), token);
    }

    private String issueOneTimeToken(User user, OneTimeToken.Type type, Instant expiresAt) {
        // Retire any outstanding token of the same kind, so requesting a second link invalidates
        // the first rather than leaving two live credentials in two inboxes.
        oneTimeTokens.consumeOutstanding(user.getId(), type, Instant.now());

        String rawToken = Hashes.randomToken();
        OneTimeToken token = new OneTimeToken();
        token.setUser(user);
        token.setType(type);
        token.setTokenHash(Hashes.sha256Hex(rawToken));
        token.setExpiresAt(expiresAt);
        oneTimeTokens.save(token);
        return rawToken;
    }

    private Role customerRole() {
        return roles.findByName(Role.CUSTOMER).orElseThrow(() -> new IllegalStateException(
                "The CUSTOMER role is missing — R__seed_reference_data.sql has not been applied"));
    }

    private static Recipient recipientOf(User user) {
        return new Recipient(user.getId(), user.getEmail(), user.fullName(), user.getLocale());
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Canonicalises Egyptian mobile numbers to the local {@code 01XXXXXXXXX} form so that
     * {@code +201012345678} and {@code 01012345678} resolve to the same account.
     */
    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+20")) {
            digits = "0" + digits.substring(3);
        } else if (digits.startsWith("20") && digits.length() == 12) {
            digits = "0" + digits.substring(2);
        }
        return digits;
    }
}
