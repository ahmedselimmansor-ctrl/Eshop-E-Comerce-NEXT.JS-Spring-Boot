package com.eshop.payment.web;

import com.eshop.payment.domain.PaymentProviderType;
import com.eshop.payment.service.PaymentWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Payment provider callbacks.
 *
 * <p>Unauthenticated by design - a payment gateway cannot present a bearer token. Authenticity comes
 * entirely from the HMAC signature over the raw body, verified inside each provider.
 *
 * <p>Three things about these endpoints are deliberate:
 * <ul>
 *   <li><b>The raw body is taken as a String</b>, not a parsed object. Signatures are computed over
 *       the exact bytes sent; deserialising and re-serialising reorders keys and changes number
 *       formatting, which breaks verification in ways that are painful to diagnose.</li>
 *   <li><b>They always return 200</b> once the event is stored - including for an invalid
 *       signature. A non-2xx makes the provider retry, and retrying a payload we could not verify
 *       achieves nothing. Real failures are recorded and retried on our own schedule.</li>
 *   <li><b>They are exempt from rate limiting</b> (see {@code RateLimitFilter}). A provider
 *       flushing a backlog after an outage must not be throttled, or paid orders stay stuck in
 *       {@code PENDING_PAYMENT}.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments/webhooks")
@RequiredArgsConstructor
@Tag(name = "Payments")
@SecurityRequirements
public class PaymentWebhookController {

    private final PaymentWebhookService webhooks;

    @PostMapping(value = "/paymob", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Paymob transaction callback",
            description = "HMAC-SHA512 verified. Always answers 200 once the event is recorded.")
    public ResponseEntity<Map<String, String>> paymob(@RequestBody String rawBody,
                                                      HttpServletRequest request) {
        return handle(PaymentProviderType.PAYMOB, rawBody, request);
    }

    @PostMapping(value = "/fawry", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Fawry payment notification",
            description = "SHA-256 signature verified. Always answers 200 once the event is recorded.")
    public ResponseEntity<Map<String, String>> fawry(@RequestBody String rawBody,
                                                     HttpServletRequest request) {
        return handle(PaymentProviderType.FAWRY, rawBody, request);
    }

    private ResponseEntity<Map<String, String>> handle(PaymentProviderType provider,
                                                       String rawBody,
                                                       HttpServletRequest request) {
        PaymentWebhookService.Outcome outcome;
        try {
            outcome = webhooks.handle(provider, rawBody, headersOf(request));
        } catch (RuntimeException e) {
            // The event could not even be stored - a database outage. This is the one case worth
            // a 5xx, because the provider's retry is genuinely the recovery path.
            log.error("webhook_store_failed provider={} error={}", provider, e.toString(), e);
            return ResponseEntity.status(503).body(Map.of("status", "retry"));
        }

        return ResponseEntity.ok(Map.of("status", outcome.name().toLowerCase()));
    }

    /**
     * Copies request headers into a plain map with lower-cased names.
     *
     * <p>Some providers put the signature in a header rather than the body, and header name casing
     * is not guaranteed across proxies.
     */
    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name.toLowerCase(), request.getHeader(name)));
        return headers;
    }
}
