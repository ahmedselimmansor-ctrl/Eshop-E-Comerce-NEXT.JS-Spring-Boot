package com.eshop.user.web;

import com.eshop.common.web.RequestContext;
import com.eshop.security.RefreshCookies;
import com.eshop.user.service.OAuthService;
import com.eshop.user.web.dto.AuthResponse;
import com.eshop.user.web.dto.TokenRequests;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
@SecurityRequirements
public class OAuthController {

    private final OAuthService oauthService;
    private final RefreshCookies refreshCookies;

    @GetMapping("/google")
    @Operation(summary = "Start Google sign-in",
            description = "Redirects to Google's consent screen. `next` is the storefront path to "
                    + "return to afterwards and is remembered server-side, not echoed through Google.")
    public ResponseEntity<Void> start(@RequestParam(required = false) String next) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthService.buildAuthorizationUrl(next)))
                .build();
    }

    @GetMapping("/google/callback")
    @Operation(summary = "Google redirect target",
            description = "Redirects to the storefront with a single-use exchange code. Tokens are "
                    + "never placed in a URL — query strings persist in history, Referer headers, and proxy logs.")
    public ResponseEntity<Void> callback(@RequestParam String code,
                                         @RequestParam String state,
                                         HttpServletRequest request) {
        String redirect = oauthService.handleCallback(
                code, state,
                request.getHeader(HttpHeaders.USER_AGENT),
                RequestContext.resolveClientIp(request));

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
    }

    @PostMapping("/exchange")
    @Operation(summary = "Redeem the exchange code for tokens",
            description = "Single use, valid for two minutes.")
    public ResponseEntity<AuthResponse> exchange(@Valid @RequestBody TokenRequests.OAuthExchange body,
                                                 HttpServletRequest request) {
        AuthResponse response = oauthService.exchange(
                body.code(),
                request.getHeader(HttpHeaders.USER_AGENT),
                RequestContext.resolveClientIp(request));

        if (!refreshCookies.isBrowserClient(request)) {
            return ResponseEntity.ok(response);
        }
        ResponseCookie cookie = refreshCookies.issue(response.tokens().refreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(response.user(), response.tokens().withoutRefreshToken()));
    }
}
