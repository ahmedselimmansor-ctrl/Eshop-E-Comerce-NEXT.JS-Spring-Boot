package com.eshop.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.Optional;

/**
 * Read-only accessors for the per-request values that {@code RequestLoggingFilter} publishes into
 * the SLF4J MDC. Keeping them in the MDC means the same identifiers appear on every log line and in
 * every error response without threading a context object through the call stack.
 */
public final class RequestContext {

    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_ROUTE = "route";
    public static final String MDC_STATUS = "status";
    public static final String MDC_DURATION = "durationMs";
    public static final String MDC_CLIENT_IP = "clientIp";

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_SESSION_ID = "X-Session-Id";
    public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private RequestContext() {
    }

    public static String requestId() {
        return Optional.ofNullable(MDC.get(MDC_REQUEST_ID)).orElse("-");
    }

    public static Optional<String> userId() {
        return Optional.ofNullable(MDC.get(MDC_USER_ID));
    }

    public static String clientIp() {
        return Optional.ofNullable(MDC.get(MDC_CLIENT_IP)).orElse("-");
    }

    /** Two-letter language for the current request: {@code ar} or {@code en}. */
    public static String locale() {
        Locale locale = LocaleContextHolder.getLocale();
        return "en".equalsIgnoreCase(locale.getLanguage()) ? "en" : "ar";
    }

    public static boolean isArabic() {
        return "ar".equals(locale());
    }

    /**
     * Best-effort originating IP.
     *
     * <p>Behind the GCP global load balancer the left-most {@code X-Forwarded-For} entry is the real
     * client. This is only used for logging, abuse counters, and audit rows - never for
     * authorisation - because the header is client-supplied and the LB only appends to it.
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
