package com.eshop.security;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Password acceptance rules.
 *
 * <p>Follows NIST SP 800-63B: enforce length, screen against known-weak values, and drop composition
 * requirements. Mandating "one uppercase, one digit, one symbol" reliably produces {@code
 * Password1!} — it raises the floor barely at all while pushing users toward patterns an attacker's
 * rule set already covers, and it blocks long passphrases that are genuinely stronger.
 *
 * <p>The blocklist here is a compact set of the values that dominate credential-stuffing lists. A
 * production deployment should additionally screen against a breach corpus (Have I Been Pwned's
 * k-anonymity range API, or a local Bloom filter built from it) — that integration point is
 * {@link #isBreached}.
 */
@Component
@RequiredArgsConstructor
public class PasswordPolicy {

    private final AppProperties properties;

    private static final Set<String> BLOCKLIST = Set.of(
            "password", "password1", "password123", "12345678", "123456789", "1234567890",
            "qwerty123", "qwertyuiop", "iloveyou", "welcome1", "admin123", "letmein1",
            "sunshine", "princess", "football", "baseball", "trustno1", "dragon123",
            "monkey123", "abc12345", "passw0rd", "p@ssw0rd", "changeme", "secret123",
            "eshop123", "egypt123", "cairo123", "aaaaaaaa", "11111111", "00000000");

    /**
     * @throws BusinessException {@code VALIDATION_ERROR} with a field-level violation on the
     *                           {@code password} field, so the frontend can show it inline
     */
    public void validate(String password, String email, String firstName) {
        int minLength = properties.getSecurity().getPasswordMinLength();

        if (password == null || password.length() < minLength) {
            throw violation("TOO_SHORT",
                    "كلمة المرور يجب أن تكون " + minLength + " أحرف على الأقل");
        }
        if (password.length() > 128) {
            // Bounded so a multi-megabyte input cannot turn Argon2 into a CPU exhaustion vector.
            throw violation("TOO_LONG", "كلمة المرور طويلة جداً");
        }

        String normalized = password.toLowerCase(Locale.ROOT);

        if (BLOCKLIST.contains(normalized)) {
            throw violation("TOO_COMMON", "كلمة المرور شائعة جداً، اختر كلمة أقوى");
        }
        if (isBreached(normalized)) {
            throw violation("BREACHED", "كلمة المرور ظهرت في تسريبات سابقة، اختر كلمة أخرى");
        }
        if (isSingleRepeatedCharacter(normalized)) {
            throw violation("TOO_SIMPLE", "كلمة المرور بسيطة جداً");
        }
        if (containsPersonalData(normalized, email, firstName)) {
            throw violation("CONTAINS_PERSONAL_DATA",
                    "كلمة المرور يجب ألا تحتوي على بريدك الإلكتروني أو اسمك");
        }
    }

    /**
     * Hook for a breach-corpus lookup. Returns {@code false} today; wiring in HIBP's range API or a
     * locally hosted Bloom filter is a drop-in replacement that needs no caller changes.
     */
    protected boolean isBreached(String normalizedPassword) {
        return false;
    }

    private static boolean isSingleRepeatedCharacter(String password) {
        return password.chars().distinct().count() <= 2;
    }

    private static boolean containsPersonalData(String password, String email, String firstName) {
        if (email != null) {
            String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            if (localPart.length() >= 4 && password.contains(localPart.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return firstName != null
                && firstName.length() >= 4
                && password.contains(firstName.toLowerCase(Locale.ROOT));
    }

    private static BusinessException violation(String code, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Password rejected: " + code)
                .withViolation("password", code, message, "[REDACTED]");
    }
}
