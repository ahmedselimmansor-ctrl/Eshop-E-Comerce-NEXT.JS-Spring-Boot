package com.eshop.user.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Credentials returned after a successful authentication or refresh.
 *
 * <p>Web clients receive {@code refreshToken} as {@code null} here - the value is delivered instead
 * as an {@code HttpOnly; Secure; SameSite=Strict} cookie scoped to {@code /api/v1/auth}, so no
 * JavaScript on the page can read it and XSS cannot exfiltrate a long-lived credential. Mobile
 * clients, which have no cookie jar and store the value in the platform keychain, receive it in the
 * body.
 */
@Schema(name = "TokenPair")
public record TokenPair(

        @Schema(description = "Bearer token for the Authorization header. Valid for 15 minutes.")
        String accessToken,

        @Schema(description = "Opaque rotating refresh token. Null for web clients; sent as a cookie instead.")
        String refreshToken,

        String tokenType,

        @Schema(description = "Access token lifetime in seconds")
        long expiresIn
) {
    public static TokenPair of(String accessToken, String refreshToken, long expiresIn) {
        return new TokenPair(accessToken, refreshToken, "Bearer", expiresIn);
    }

    /** Same pair with the refresh token withheld from the body, for cookie-based web clients. */
    public TokenPair withoutRefreshToken() {
        return new TokenPair(accessToken, null, tokenType, expiresIn);
    }
}
