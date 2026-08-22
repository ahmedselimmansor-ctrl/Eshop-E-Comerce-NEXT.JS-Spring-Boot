package com.eshop.user.web;

import com.eshop.common.web.RequestContext;
import com.eshop.security.RefreshCookies;
import com.eshop.user.service.AuthService;
import com.eshop.user.web.dto.AuthResponse;
import com.eshop.user.web.dto.LoginRequest;
import com.eshop.user.web.dto.RegisterRequest;
import com.eshop.user.web.dto.TokenRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. All public; all rate limited by tier (see {@code RateLimitFilter}).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
@SecurityRequirements
public class AuthController {

    private final AuthService authService;
    private final RefreshCookies refreshCookies;

    @PostMapping("/register")
    @Operation(summary = "Create an account",
            description = "Sends a verification email and returns a usable session immediately - "
                    + "browsing and checkout do not require a verified address.")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        AuthResponse response = authService.register(request, userAgent(httpRequest), clientIp(httpRequest));
        return respond(HttpStatus.CREATED, response, httpRequest);
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in")
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS or ACCOUNT_LOCKED")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, userAgent(httpRequest), clientIp(httpRequest));
        return respond(HttpStatus.OK, response, httpRequest);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new pair",
            description = "The presented token is revoked. Presenting an already-revoked token is "
                    + "treated as theft and revokes every session in that rotation family.")
    @ApiResponse(responseCode = "401", description = "TOKEN_EXPIRED, TOKEN_INVALID or TOKEN_REUSE_DETECTED")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody(required = false) TokenRequests.Refresh body,
                                                HttpServletRequest httpRequest) {
        String presented = refreshCookies.resolve(body == null ? null : body.refreshToken(), httpRequest);
        AuthResponse response = authService.refresh(presented, userAgent(httpRequest), clientIp(httpRequest));
        return respond(HttpStatus.OK, response, httpRequest);
    }

    @PostMapping("/logout")
    @Operation(summary = "End this session")
    public ResponseEntity<Void> logout(@Valid @RequestBody(required = false) TokenRequests.Logout body,
                                       HttpServletRequest httpRequest) {
        authService.logout(refreshCookies.resolve(body == null ? null : body.refreshToken(), httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset link",
            description = "Always returns 202, whether or not the address is registered. "
                    + "A different response for unknown addresses would confirm which emails have accounts.")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody TokenRequests.ForgotPassword body) {
        authService.forgotPassword(body.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Set a new password using a reset token",
            description = "Revokes every existing session on success.")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody TokenRequests.ResetPassword body) {
        authService.resetPassword(body.token(), body.newPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an email address")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequests.VerifyEmail body) {
        authService.verifyEmail(body.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend-verification")
    @Operation(summary = "Send the verification email again",
            description = "Always returns 202, for the same reason as forgot-password.")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody TokenRequests.ResendVerification body) {
        authService.resendVerification(body.email());
        return ResponseEntity.accepted().build();
    }

    // ---------------------------------------------------------------- helpers --

    /**
     * Sends the refresh token as a cookie to browsers and in the body to native clients.
     *
     * <p>A browser that also received it in the body would encourage the frontend to keep a copy in
     * JavaScript-reachable storage, which is precisely what the HttpOnly cookie exists to prevent.
     */
    private ResponseEntity<AuthResponse> respond(HttpStatus status,
                                                 AuthResponse response,
                                                 HttpServletRequest request) {
        if (!refreshCookies.isBrowserClient(request)) {
            return ResponseEntity.status(status).body(response);
        }

        ResponseCookie cookie = refreshCookies.issue(response.tokens().refreshToken());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(response.user(), response.tokens().withoutRefreshToken()));
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }

    private static String clientIp(HttpServletRequest request) {
        return RequestContext.resolveClientIp(request);
    }
}
