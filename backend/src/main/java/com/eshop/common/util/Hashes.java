package com.eshop.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Digest and MAC helpers used for opaque tokens, webhook signature checks, and idempotency hashes.
 *
 * <p>These are <em>not</em> for passwords — those go through Argon2id via
 * {@code PasswordEncoder}. SHA-256 here is only used where the input is already high-entropy
 * (a 256-bit random token) or where the goal is a stable fingerprint rather than secrecy.
 */
public final class Hashes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Hashes() {
    }

    /** 256 bits of entropy, URL-safe. Used for refresh tokens, reset links, share tokens. */
    public static String randomToken() {
        return randomToken(32);
    }

    public static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL_ENCODER.encodeToString(buffer);
    }

    /** Numeric OTP with a uniform distribution (no modulo bias). */
    public static String randomNumericCode(int digits) {
        StringBuilder code = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }

    public static String sha256Hex(String input) {
        return HEX.formatHex(digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(byte[] input) {
        return HEX.formatHex(digest(input));
    }

    /**
     * HMAC-SHA512, lower-case hex. Paymob signs its callbacks this way, over a specific
     * concatenation of payload fields.
     */
    public static String hmacSha512Hex(String message, String secret) {
        return HEX.formatHex(mac("HmacSHA512", message, secret));
    }

    public static String hmacSha256Hex(String message, String secret) {
        return HEX.formatHex(mac("HmacSHA256", message, secret));
    }

    /**
     * Constant-time comparison.
     *
     * <p>Signature verification must not short-circuit on the first differing byte: the timing
     * difference is measurable across enough requests and leaks the expected value byte by byte.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS and must be present", e);
        }
    }

    private static byte[] mac(String algorithm, String message, String secret) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute " + algorithm, e);
        }
    }
}
