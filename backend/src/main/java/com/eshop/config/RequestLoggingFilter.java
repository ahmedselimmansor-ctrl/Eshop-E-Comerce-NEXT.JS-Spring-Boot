package com.eshop.config;

import com.eshop.common.Uuid7;
import com.eshop.common.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Set;

/**
 * Emits exactly one structured log line per request and seeds the MDC that every other log line in
 * the request inherits.
 *
 * <p>Runs first in the chain so that authentication failures, rate-limit rejections, and unhandled
 * exceptions all carry the same {@code requestId}. The id is echoed in the {@code X-Request-Id}
 * response header and embedded in error bodies, which is what turns a customer's screenshot into a
 * single log query.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** Probes and metrics scrapes would otherwise dominate the log volume. */
    private static final Set<String> SILENT_PATHS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/actuator/prometheus", "/favicon.ico");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String requestId = resolveRequestId(request);
        String clientIp = RequestContext.resolveClientIp(request);

        MDC.put(RequestContext.MDC_REQUEST_ID, requestId);
        MDC.put(RequestContext.MDC_CLIENT_IP, clientIp);
        response.setHeader(RequestContext.HEADER_REQUEST_ID, requestId);

        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            if (!isSilent(request)) {
                // The matched pattern, not the raw URI: /products/{slug} aggregates in dashboards,
                // whereas /products/iphone-15-pro-max creates one time series per product.
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = pattern != null ? pattern.toString() : request.getRequestURI();

                MDC.put(RequestContext.MDC_ROUTE, route);
                MDC.put(RequestContext.MDC_STATUS, String.valueOf(response.getStatus()));
                MDC.put(RequestContext.MDC_DURATION, String.valueOf(durationMs));

                if (response.getStatus() >= 500) {
                    log.error("http_request method={} route={} status={} duration_ms={}",
                            request.getMethod(), route, response.getStatus(), durationMs);
                } else if (response.getStatus() >= 400) {
                    log.warn("http_request method={} route={} status={} duration_ms={}",
                            request.getMethod(), route, response.getStatus(), durationMs);
                } else {
                    log.info("http_request method={} route={} status={} duration_ms={}",
                            request.getMethod(), route, response.getStatus(), durationMs);
                }
            }
            MDC.clear();
        }
    }

    /**
     * Reuses a caller-supplied id when it looks sane, so a trace started in the Next.js BFF stays
     * one trace end to end. Anything longer than 64 characters or containing control characters is
     * replaced - the value ends up in log fields and must not be able to forge a line.
     */
    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(RequestContext.HEADER_REQUEST_ID);
        if (incoming != null && !incoming.isBlank() && incoming.length() <= 64 && isSafe(incoming)) {
            return incoming;
        }
        return Uuid7.generate().toString();
    }

    private static boolean isSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private boolean isSilent(HttpServletRequest request) {
        return SILENT_PATHS.contains(request.getRequestURI());
    }
}
