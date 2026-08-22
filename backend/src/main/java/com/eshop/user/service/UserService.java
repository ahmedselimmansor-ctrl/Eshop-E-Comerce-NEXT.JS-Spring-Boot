package com.eshop.user.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.notification.service.NotificationService;
import com.eshop.notification.service.Recipient;
import com.eshop.security.PasswordPolicy;
import com.eshop.user.domain.Role;
import com.eshop.user.domain.User;
import com.eshop.user.domain.UserStatus;
import com.eshop.user.repository.RoleRepository;
import com.eshop.user.repository.UserRepository;
import com.eshop.user.web.dto.ChangePasswordRequest;
import com.eshop.user.web.dto.UpdateProfileRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Profile management for the account owner, plus the customer-administration operations exposed
 * under {@code /admin/customers}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final NotificationService notifications;

    @Transactional(readOnly = true)
    public User require(UUID userId) {
        return users.findActiveById(userId)
                .orElseThrow(() -> NotFoundException.of("User", userId));
    }

    @Transactional
    public User updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = require(userId);

        if (request.firstName() != null) {
            user.setFirstName(request.firstName().trim());
        }
        if (request.lastName() != null) {
            user.setLastName(request.lastName().isBlank() ? null : request.lastName().trim());
        }
        if (request.locale() != null) {
            user.setLocale(request.locale());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().isBlank() ? null : request.avatarUrl());
        }
        if (request.marketingOptIn() != null) {
            user.setMarketingOptIn(request.marketingOptIn());
        }
        if (request.phone() != null) {
            String phone = normalizePhone(request.phone());
            if (!phone.equals(user.getPhone())) {
                if (users.existsByPhoneAndDeletedAtIsNull(phone)) {
                    throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED,
                            "Phone already in use");
                }
                user.setPhone(phone);
                // A new number is unverified until it passes OTP, so it cannot be used to recover
                // the account in the meantime.
                user.setPhoneVerified(false);
            }
        }

        return users.save(user);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = require(userId);

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("password_change_rejected user={} reason=current_password_mismatch", userId);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "New password matches the old one")
                    .withViolation("newPassword", "SAME_AS_CURRENT",
                            "كلمة المرور الجديدة يجب أن تختلف عن الحالية", "[REDACTED]");
        }

        passwordPolicy.validate(request.newPassword(), user.getEmail(), user.getFirstName());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.save(user);

        int revoked = refreshTokens.revokeAllForUser(userId);
        notifications.sendSecurityAlert(recipientOf(user), "password-changed",
                Map.of("sessionsRevoked", revoked));

        log.info("password_changed user={} sessions_revoked={}", userId, revoked);
    }

    /**
     * Self-service account deletion.
     *
     * <p>Soft delete with personal fields scrubbed, not a row removal: orders, invoices, and payment
     * records must survive for accounting and dispute handling, and a hard delete would either
     * orphan them or cascade away financial history. The email is replaced with a non-routable
     * placeholder so the address becomes reusable and the partial unique index stays satisfied.
     */
    @Transactional
    public void deleteOwnAccount(UUID userId) {
        User user = require(userId);
        Instant now = Instant.now();

        user.setEmail("deleted-" + user.getId() + "@deleted.invalid");
        user.setPhone(null);
        user.setPasswordHash(null);
        user.setFirstName("مستخدم محذوف");
        user.setLastName(null);
        user.setAvatarUrl(null);
        user.setMarketingOptIn(false);
        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(now);
        users.save(user);

        refreshTokens.revokeAllForUser(userId);
        log.info("user_deleted user={}", userId);
    }

    // ----------------------------------------------------------- admin side --

    /**
     * Customer search for the admin list.
     *
     * <p>Roles are initialised here because {@code UserResponse.summary} renders them and
     * {@code open-in-view} is off - the page is otherwise detached by the time the DTO is built.
     */
    @Transactional(readOnly = true)
    public Page<User> search(String query, UserStatus status, Pageable pageable) {
        String normalized = (query == null || query.isBlank()) ? null : query.trim();
        Page<User> page = users.search(normalized, status, pageable);
        page.forEach(user -> user.getRoles().size());
        return page;
    }

    @Transactional
    public User setStatus(UUID userId, UserStatus status, UUID actorId) {
        User user = require(userId);

        if (user.hasRole(Role.SUPER_ADMIN) && status != UserStatus.ACTIVE) {
            // Otherwise one compromised admin account can lock out every other administrator.
            throw new BusinessException(ErrorCode.FORBIDDEN, "A super admin cannot be suspended");
        }

        user.setStatus(status);
        users.save(user);

        if (status == UserStatus.SUSPENDED) {
            refreshTokens.revokeAllForUser(userId);
        }
        log.info("user_status_changed user={} status={} actor={}", userId, status, actorId);
        return user;
    }

    @Transactional
    public User assignRoles(UUID userId, Set<String> roleNames, UUID actorId) {
        User user = require(userId);
        List<Role> resolved = roles.findByNameIn(List.copyOf(roleNames));

        if (resolved.size() != roleNames.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "One or more roles do not exist")
                    .withViolation("roles", "UNKNOWN_ROLE", "دور غير موجود", roleNames);
        }

        user.getRoles().clear();
        resolved.forEach(user::addRole);
        users.save(user);

        // Authorities are carried in the access token, so a role change only takes effect on the
        // next issue. Revoking sessions forces that to happen immediately.
        refreshTokens.revokeAllForUser(userId);

        log.info("user_roles_assigned user={} roles={} actor={}", userId, roleNames, actorId);
        return user;
    }

    @Transactional(readOnly = true)
    public long countRegisteredSince(Instant since) {
        return users.countRegisteredSince(since);
    }

    private static Recipient recipientOf(User user) {
        return new Recipient(user.getId(), user.getEmail(), user.fullName(), user.getLocale());
    }

    private static String normalizePhone(String phone) {
        String digits = phone.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+20")) {
            return "0" + digits.substring(3);
        }
        if (digits.startsWith("20") && digits.length() == 12) {
            return "0" + digits.substring(2);
        }
        return digits.toLowerCase(Locale.ROOT);
    }
}
