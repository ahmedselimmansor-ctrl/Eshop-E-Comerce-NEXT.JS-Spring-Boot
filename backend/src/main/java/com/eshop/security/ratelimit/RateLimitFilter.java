package com.eshop.security.ratelimit;

import com.eshop.common.exception.ErrorCode;
import com.eshop.common.web.ProblemResponseWriter;
import com.eshop.common.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Applies the tiered limits from {@code docs/02-API-CONTRACT.md}.
 *
 * <p>Runs after authentication so an identified user is throttled on their user id rather than on
 * their IP - otherwise everyone behind one corporate NAT or mobile carrier gateway shares a single
 * bucket and locks each other out.
 *
 * <p>Rejection is raised as an exception rather than written here directly, so the 429 body is the
 * same RFC 9457 document as every other error and the client can parse one shape.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final ProblemResponseWriter problemWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String tier = resolveTier(request);
        String identity = resolveIdentity(request);

        RateLimiter.Decision decision = rateLimiter.tryConsume(tier, identity);

        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.remaining())));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochMs() / 1000));

        if (!decision.allowed()) {
            // Written here rather than thrown: a filter-level exception bypasses
            // @RestControllerAdvice and would reach the client as a generic /error body.
            log.warn("rate_limited tier={} identity={} route={}", tier, identity, request.getRequestURI());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
            problemWriter.write(request, response, ErrorCode.RATE_LIMITED, Map.of(
                    "retryAfterSeconds", decision.retryAfterSeconds(),
                    "limit", decision.limit()));
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Webhooks, health probes, and metrics are exempt.
     *
     * <p>Webhooks are authenticated by HMAC signature, and a payment provider retrying a burst of
     * callbacks after an outage must not be throttled - dropping those would leave paid orders stuck
     * in {@code PENDING_PAYMENT}.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/api/v1/payments/webhooks/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    private String resolveTier(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/v1/admin/")) {
            return "admin";
        }
        if ("POST".equals(method)) {
            if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/oauth/exchange")) {
                return "login";
            }
            if (path.equals("/api/v1/auth/register")) {
                return "register";
            }
            if (path.equals("/api/v1/auth/forgot-password") || path.equals("/api/v1/auth/reset-password")
                    || path.equals("/api/v1/auth/resend-verification")) {
                return "password-reset";
            }
            if (path.startsWith("/api/v1/checkout/") || path.startsWith("/api/v1/payments/")) {
                return "checkout";
            }
        }
        if (path.startsWith("/api/v1/search")) {
            return "search";
        }
        return isAuthenticated() ? "authenticated" : "anonymous";
    }

    /**
     * Bucket identity: the authenticated user when there is one, otherwise the client IP.
     *
     * <p>Login and password-reset attempts are additionally keyed by the submitted account so that
     * one attacker cannot lock every user out from a single IP, and so that a distributed attack
     * against one account still shares a bucket. The account comes from a header the client sets
     * alongside the body; it only ever narrows a bucket, never widens one, so a forged value cannot
     * escape the limit.
     */
    private String resolveIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (isAuthenticated() && authentication != null) {
            return "u:" + authentication.getName();
        }
        return "ip:" + RequestContext.resolveClientIp(request);
    }

    private boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }
}
