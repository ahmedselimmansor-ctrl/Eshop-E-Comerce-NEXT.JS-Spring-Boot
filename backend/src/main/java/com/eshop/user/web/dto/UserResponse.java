package com.eshop.user.web.dto;

import com.eshop.user.domain.User;
import com.eshop.user.domain.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A user as returned to the owner of the account or to an administrator.
 *
 * <p>Never includes {@code passwordHash}, {@code failedLoginAttempts}, or {@code lockedUntil}:
 * the first is a credential, and the other two tell an attacker whether their guessing is landing.
 */
@Schema(name = "UserResponse")
public record UserResponse(
        UUID id,
        String email,
        String phone,
        String firstName,
        String lastName,
        String fullName,
        String avatarUrl,
        boolean emailVerified,
        boolean phoneVerified,
        UserStatus status,
        String locale,
        String defaultCurrency,
        boolean marketingOptIn,
        Set<String> roles,

        @Schema(description = "Fine-grained authorities; empty for ordinary customers")
        Set<String> permissions,

        Instant lastLoginAt,
        Instant createdAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getPhone(),
                user.getFirstName(),
                user.getLastName(),
                user.fullName(),
                user.getAvatarUrl(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getStatus(),
                user.getLocale(),
                user.getDefaultCurrency(),
                user.isMarketingOptIn(),
                user.roleNames(),
                user.permissionNames(),
                user.getLastLoginAt(),
                user.getCreatedAt());
    }

    /** Trimmed projection for admin customer lists, where permissions are noise. */
    public static UserResponse summary(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getPhone(),
                user.getFirstName(), user.getLastName(), user.fullName(),
                user.getAvatarUrl(), user.isEmailVerified(), user.isPhoneVerified(),
                user.getStatus(), user.getLocale(), user.getDefaultCurrency(),
                user.isMarketingOptIn(), user.roleNames(), Set.of(),
                user.getLastLoginAt(), user.getCreatedAt());
    }

    public static List<UserResponse> summaries(List<User> users) {
        return users.stream().map(UserResponse::summary).toList();
    }
}
