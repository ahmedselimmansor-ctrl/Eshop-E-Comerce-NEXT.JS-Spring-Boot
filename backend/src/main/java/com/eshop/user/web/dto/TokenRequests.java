package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The small single-field payloads of the token lifecycle endpoints, grouped so that eight
 * near-identical one-line files do not obscure the ones that carry real logic.
 */
public final class TokenRequests {

    private TokenRequests() {
    }

    /**
     * Refresh. The token is optional in the body because web clients send it as an HttpOnly cookie
     * and cannot read it back to include here.
     */
    @Schema(name = "RefreshRequest")
    public record Refresh(@Size(max = 512) String refreshToken) {
    }

    /** Logout. Same optionality as refresh; an absent value falls back to the cookie. */
    @Schema(name = "LogoutRequest")
    public record Logout(@Size(max = 512) String refreshToken) {
    }

    @Schema(name = "ForgotPasswordRequest")
    public record ForgotPassword(@NotBlank @Email @Size(max = 191) String email) {
    }

    @Schema(name = "ResetPasswordRequest")
    public record ResetPassword(
            @NotBlank @Size(max = 512) String token,
            @NotBlank @Size(min = 8, max = 128, message = "كلمة المرور يجب أن تكون 8 أحرف على الأقل")
            String newPassword) {
    }

    @Schema(name = "VerifyEmailRequest")
    public record VerifyEmail(@NotBlank @Size(max = 512) String token) {
    }

    @Schema(name = "ResendVerificationRequest")
    public record ResendVerification(@NotBlank @Email @Size(max = 191) String email) {
    }

    /**
     * Exchanges the one-time code issued by the OAuth callback for a token pair.
     *
     * <p>The callback redirects to the frontend with a short-lived opaque code rather than with
     * tokens in the URL: anything in a query string lands in browser history, in the
     * {@code Referer} header of the next request, and in any proxy access log along the way.
     */
    @Schema(name = "OAuthExchangeRequest")
    public record OAuthExchange(@NotBlank @Size(max = 128) String code) {
    }
}
