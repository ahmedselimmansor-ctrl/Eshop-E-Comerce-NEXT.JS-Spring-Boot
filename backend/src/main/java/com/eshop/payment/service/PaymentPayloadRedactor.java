package com.eshop.payment.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strips anything sensitive from a provider payload before it is persisted or logged.
 *
 * <p>Gateways return more than the integration asked for, and a raw payload stored "just in case"
 * is how card data ends up in a database that was never meant to hold it - and how a support
 * engineer reading a log ends up looking at a token that can move money.
 *
 * <p>Redaction is by key name against an allow-nothing list, applied recursively, and the check is
 * case- and separator-insensitive so {@code card_number}, {@code cardNumber}, and {@code CardNum}
 * are all caught.
 */
public final class PaymentPayloadRedactor {

    private static final String MASK = "[REDACTED]";

    /** Normalised (lower-case, punctuation-free) key fragments that must never be stored. */
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
            "cardnumber", "cardnum", "pan", "primaryaccountnumber",
            "cvv", "cvc", "cvv2", "securitycode",
            "expiry", "expirymonth", "expiryyear", "expmonth", "expyear",
            "token", "authtoken", "accesstoken", "refreshtoken", "paymentkey",
            "apikey", "secretkey", "securitykey", "hmac", "signature",
            "password", "pin", "otp",
            "iban", "accountnumber", "routingnumber");

    /** Keys that are safe and useful to keep even though they contain a sensitive fragment. */
    private static final Set<String> ALLOWED = Set.of(
            "maskedpan", "cardlast4", "last4", "lastfourdigits", "cardbrand", "brand", "scheme");

    private static final int MAX_DEPTH = 6;

    private PaymentPayloadRedactor() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, Object> payload) {
        return (Map<String, Object>) redactValue(payload, 0);
    }

    private static Object redactValue(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return value;
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                cleaned.put(name, isSensitive(name) ? MASK : redactValue(nested, depth + 1));
            });
            return cleaned;
        }

        if (value instanceof List<?> list) {
            return list.stream().map(element -> redactValue(element, depth + 1)).toList();
        }

        return value;
    }

    private static boolean isSensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");

        if (ALLOWED.contains(normalized)) {
            return false;
        }
        // Substring rather than equality: providers nest these under prefixes such as
        // `source_data_pan` or `transaction.card_number`.
        return SENSITIVE_FRAGMENTS.stream().anyMatch(normalized::contains);
    }

    /** Keeps only the last four digits of a masked PAN, discarding whatever else the mask contains. */
    public static String lastFour(String maskedPan) {
        if (maskedPan == null) {
            return null;
        }
        String digits = maskedPan.replaceAll("\\D", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }
}
