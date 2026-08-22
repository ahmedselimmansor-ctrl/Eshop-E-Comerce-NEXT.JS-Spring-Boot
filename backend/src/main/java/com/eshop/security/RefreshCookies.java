package com.eshop.security;

import com.eshop.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Builds and reads the refresh-token cookie used by browser clients.
 *
 * <p>Every attribute here is load-bearing:
 * <ul>
 *   <li><b>HttpOnly</b> - script on the page cannot read it, so an XSS that steals the in-memory
 *       access token still cannot walk away with a 30-day credential.</li>
 *   <li><b>Secure</b> - never sent over plain HTTP. Off only in local development, where there is
 *       no TLS to send it over.</li>
 *   <li><b>SameSite=Strict</b> - not attached to any cross-site request, which is what removes the
 *       need for CSRF tokens on the refresh endpoint.</li>
 *   <li><b>Path=/api/v1/auth</b> - the cookie is not attached to catalog, cart, or checkout calls,
 *       so it is absent from the overwhelming majority of requests and from any log that records
 *       headers.</li>
 * </ul>
 *
 * <p>Mobile clients receive the refresh token in the response body instead and keep it in the
 * platform keychain; they have no cookie jar and none of this applies.
 */
@Component
@RequiredArgsConstructor
public class RefreshCookies {

    private static final String PATH = "/api/v1/auth";

    private final AppProperties properties;

    public ResponseCookie issue(String refreshToken) {
        AppProperties.Jwt config = properties.getJwt();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(
                        config.getRefreshCookieName(), refreshToken)
                .httpOnly(true)
                .secure(config.isRefreshCookieSecure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(Duration.ofDays(config.getRefreshTokenTtlDays()));

        if (!config.getRefreshCookieDomain().isBlank()) {
            builder.domain(config.getRefreshCookieDomain());
        }
        return builder.build();
    }

    /** Expired twin of {@link #issue}; every attribute must match or the browser keeps the original. */
    public ResponseCookie clear() {
        AppProperties.Jwt config = properties.getJwt();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(
                        config.getRefreshCookieName(), "")
                .httpOnly(true)
                .secure(config.isRefreshCookieSecure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(Duration.ZERO);

        if (!config.getRefreshCookieDomain().isBlank()) {
            builder.domain(config.getRefreshCookieDomain());
        }
        return builder.build();
    }

    public Optional<String> read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        String name = properties.getJwt().getRefreshCookieName();
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /**
     * Resolves the token from the request body when present, falling back to the cookie.
     *
     * <p>Body first because mobile clients always send it there; the cookie is the browser path.
     */
    public String resolve(String fromBody, HttpServletRequest request) {
        if (fromBody != null && !fromBody.isBlank()) {
            return fromBody;
        }
        return read(request).orElse(null);
    }

    /**
     * Whether this caller is a browser and should therefore receive the cookie rather than the raw
     * token. Presence of the cookie, or of an {@code Origin} header, is a reliable signal: native
     * mobile clients send neither.
     */
    public boolean isBrowserClient(HttpServletRequest request) {
        return request.getHeader("Origin") != null || read(request).isPresent();
    }
}
