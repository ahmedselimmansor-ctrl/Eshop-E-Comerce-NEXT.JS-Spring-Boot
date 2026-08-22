package com.eshop.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view of the {@code eshop.*} configuration tree.
 *
 * <p>Mutable classes with sensible defaults rather than records: a record component that is absent
 * from the active profile binds to {@code null} and only fails at the point of use, whereas a
 * defaulted field keeps the application bootable and makes the effective value obvious here.
 *
 * <p>Secrets are never given defaults. They arrive as {@code ${ENV_VAR}} placeholders resolved from
 * Secret Manager in deployed environments; a blank value disables the feature that needs it rather
 * than silently running with a placeholder.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "eshop")
public class AppProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8080";
    private String webUrl = "http://localhost:3000";
    private String adminUrl = "http://localhost:3001";
    private String defaultLocale = "ar";
    private List<String> supportedLocales = new ArrayList<>(List.of("ar", "en"));
    private String defaultCurrency = "EGP";

    private Cors cors = new Cors();
    private Jwt jwt = new Jwt();
    private Security security = new Security();
    private RateLimit rateLimit = new RateLimit();
    private CacheTtl cacheTtl = new CacheTtl();
    private Cart cart = new Cart();
    private Checkout checkout = new Checkout();
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Storage storage = new Storage();
    private PubSub pubsub = new PubSub();
    private Payment payment = new Payment();
    private Notification notification = new Notification();
    private Admin admin = new Admin();

    // ------------------------------------------------------------------ CORS --

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:3000", "http://localhost:3001"));
        private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
        private List<String> exposedHeaders = new ArrayList<>(List.of(
                "X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset",
                "Retry-After", "Content-Disposition"));
        private boolean allowCredentials = true;
        private long maxAge = 3600;
    }

    // ------------------------------------------------------------------- JWT --

    @Getter
    @Setter
    public static class Jwt {
        private String issuer = "https://api.eshop.eg";
        private String audience = "eshop-clients";
        private long accessTokenTtlSeconds = 900;
        private int refreshTokenTtlDays = 30;

        /** PKCS#8 PEM. Blank in local dev, where an ephemeral keypair is generated at startup. */
        private String privateKey = "";
        /** X.509 PEM, published through the JWKS endpoint. */
        private String publicKey = "";

        private String refreshCookieName = "refresh_token";
        private String refreshCookieDomain = "";
        private boolean refreshCookieSecure = false;
    }

    // -------------------------------------------------------------- security --

    @Getter
    @Setter
    public static class Security {
        private int maxFailedLogins = 5;
        private int lockoutMinutes = 15;
        private int passwordMinLength = 8;
        private Argon2 argon2 = new Argon2();

        /** OWASP Password Storage Cheat Sheet, Argon2id: m=19456 KiB, t=2, p=1. */
        @Getter
        @Setter
        public static class Argon2 {
            private int saltLength = 16;
            private int hashLength = 32;
            private int parallelism = 1;
            private int memoryKb = 19456;
            private int iterations = 2;
        }
    }

    // ------------------------------------------------------------ rate limit --

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;

        /**
         * Named buckets keyed by tier ({@code anonymous}, {@code login}, {@code checkout}, …).
         * Relaxed-binding turns {@code password-reset} in YAML into the key {@code password-reset}.
         */
        private Map<String, Tier> tiers = new LinkedHashMap<>();

        @Getter
        @Setter
        public static class Tier {
            private long capacity = 60;
            private long windowSeconds = 60;
        }

        public Tier tier(String name) {
            return tiers.getOrDefault(name, new Tier());
        }
    }

    // --------------------------------------------------------------- caching --

    @Getter
    @Setter
    public static class CacheTtl {
        private long categoryTree = 600;
        private long productDetail = 300;
        private long productList = 120;
        private long brands = 900;
        private long attributes = 900;
        private long settings = 300;
        private long shippingMethods = 600;
        private long dashboard = 60;
    }

    // ------------------------------------------------------------------ cart --

    @Getter
    @Setter
    public static class Cart {
        private int guestTtlDays = 30;
        private int userTtlDays = 90;
        private int maxItems = 100;
        private int maxQuantityPerItem = 999;
    }

    // -------------------------------------------------------------- checkout --

    @Getter
    @Setter
    public static class Checkout {
        /** How long stock stays reserved for an unpaid order before the sweeper releases it. */
        private int reservationMinutes = 30;
        private int idempotencyTtlHours = 24;
        private BigDecimal codFee = new BigDecimal("20.00");
        private BigDecimal freeShippingThreshold = new BigDecimal("1000.00");
        private BigDecimal defaultTaxRate = new BigDecimal("0.14");
    }

    // --------------------------------------------------------- elasticsearch --

    @Getter
    @Setter
    public static class Elasticsearch {
        private boolean enabled = true;
        private String uris = "http://localhost:9200";
        private String username = "";
        private String password = "";
        private int connectTimeoutMs = 3000;
        private int socketTimeoutMs = 10000;
        private String productIndex = "eshop-products";
    }

    // --------------------------------------------------------------- storage --

    @Getter
    @Setter
    public static class Storage {
        /** {@code gcs} in every deployed environment; {@code local} for the docker-compose stack. */
        private String provider = "gcs";
        private String projectId = "";
        private String mediaBucket = "";
        private String privateBucket = "";
        private String cdnBaseUrl = "";
        private int signedUrlTtlMinutes = 15;
        private long maxUploadSizeBytes = 10L * 1024 * 1024;
        private List<String> allowedContentTypes = new ArrayList<>(List.of(
                "image/jpeg", "image/png", "image/webp", "image/avif", "application/pdf"));

        public boolean isLocal() {
            return "local".equalsIgnoreCase(provider);
        }
    }

    // ---------------------------------------------------------------- pubsub --

    @Getter
    @Setter
    public static class PubSub {
        private boolean enabled = false;
        private String topicPrefix = "eshop";
        private long outboxPollMs = 2000;
        private int outboxBatchSize = 100;
        private int maxAttempts = 10;

        public String topicFor(String eventType) {
            return topicPrefix + "-" + eventType.replace('.', '-');
        }
    }

    // --------------------------------------------------------------- payment --

    @Getter
    @Setter
    public static class Payment {
        private String returnUrl = "http://localhost:3000/checkout/callback";
        private Paymob paymob = new Paymob();
        private Fawry fawry = new Fawry();
        private Cod cod = new Cod();

        @Getter
        @Setter
        public static class Paymob {
            private boolean enabled = true;
            private String baseUrl = "https://accept.paymob.com/api";
            private String apiKey = "";
            private String publicKey = "";
            private String secretKey = "";
            /** Shared secret Paymob uses to sign callbacks; a blank value rejects every webhook. */
            private String hmacSecret = "";
            private String iframeId = "";
            private String integrationIdCard = "";
            private String integrationIdWallet = "";
            private String integrationIdInstallment = "";

            public boolean isConfigured() {
                return enabled && !apiKey.isBlank() && !hmacSecret.isBlank();
            }
        }

        @Getter
        @Setter
        public static class Fawry {
            private boolean enabled = true;
            private String baseUrl = "https://atfawry.fawrystaging.com";
            private String merchantCode = "";
            private String securityKey = "";
            /** Lifetime of the payment reference a customer takes to a Fawry outlet. */
            private int expiryHours = 48;

            public boolean isConfigured() {
                return enabled && !merchantCode.isBlank() && !securityKey.isBlank();
            }
        }

        @Getter
        @Setter
        public static class Cod {
            private boolean enabled = true;
            /** Cap on unsecured exposure per order when paying cash on delivery. */
            private BigDecimal maxOrderAmount = new BigDecimal("20000.00");
        }
    }

    // ---------------------------------------------------------- notification --

    @Getter
    @Setter
    public static class Notification {
        private String fromEmail = "no-reply@eshop.eg";
        private String fromName = "Eshop";
        private Fcm fcm = new Fcm();

        @Getter
        @Setter
        public static class Fcm {
            private boolean enabled = false;
            private String credentialsJson = "";
        }
    }

    // ----------------------------------------------------------------- admin --

    @Getter
    @Setter
    public static class Admin {
        private int dashboardMaxRangeDays = 365;
        private int exportMaxRows = 50_000;
    }
}
