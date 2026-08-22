package com.eshop.promotion.domain;

import com.eshop.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An automatic discount that needs no code — a flash sale, a buy-two-get-one, a spend-more-save-more
 * tier.
 *
 * <p>The rule payload lives in a JSONB {@code config} column rather than in per-type tables. Each
 * promotion type has a genuinely different shape, and modelling four of them relationally means four
 * tables, four repositories, and a schema migration every time marketing invents a fifth. The
 * trade-off is that the config is validated in code ({@link #validateConfig}) rather than by the
 * database.
 */
@Entity
@Table(name = "promotions")
@Getter
@Setter
@NoArgsConstructor
public class Promotion extends AuditableEntity {

    public enum Type {
        /** {@code {"buyQuantity":2,"getQuantity":1,"productIds":[...]}} — cheapest item free. */
        BUY_X_GET_Y,
        /** {@code {"tiers":[{"minAmount":1000,"percent":5},{"minAmount":3000,"percent":10}]}} */
        TIERED_DISCOUNT,
        /** {@code {"discountPercent":30,"productIds":[...]}} bounded by the active window. */
        FLASH_SALE,
        /** {@code {"productIds":[...],"bundlePrice":"999.00"}} */
        BUNDLE
    }

    @Column(name = "name_ar", nullable = false, length = 160)
    private String nameAr;

    @Column(name = "name_en", length = 160)
    private String nameEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private Type type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    /** Higher wins when two non-stackable promotions both match. */
    @Column(name = "priority", nullable = false)
    private int priority = 0;

    /**
     * Whether this can combine with other promotions. Defaults to {@code false}: stacking discounts
     * is how an order ends up at a negative total, and it should be an explicit decision.
     */
    @Column(name = "is_stackable", nullable = false)
    private boolean stackable = false;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public boolean isRunning() {
        Instant now = Instant.now();
        return active
                && (startsAt == null || !startsAt.isAfter(now))
                && (endsAt == null || endsAt.isAfter(now));
    }

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }

    // ---------------------------------------------------------- config access --

    public int configInt(String key, int fallback) {
        Object value = config == null ? null : config.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public java.math.BigDecimal configDecimal(String key, java.math.BigDecimal fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null ? fallback : new java.math.BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    public List<UUID> configProductIds() {
        Object value = config == null ? null : config.get("productIds");
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return ((List<Object>) raw).stream()
                .map(String::valueOf)
                .map(UUID::fromString)
                .toList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> configTiers() {
        Object value = config == null ? null : config.get("tiers");
        return value instanceof List<?> raw ? (List<Map<String, Object>>) raw : List.of();
    }

    /** Fails fast on a config that the pricing engine could not act on. */
    public void validateConfig() {
        switch (type) {
            case BUY_X_GET_Y -> require(configInt("buyQuantity", 0) > 0 && configInt("getQuantity", 0) > 0,
                    "BUY_X_GET_Y requires positive buyQuantity and getQuantity");
            case FLASH_SALE -> {
                java.math.BigDecimal percent = configDecimal("discountPercent", null);
                require(percent != null
                                && percent.signum() > 0
                                && percent.compareTo(java.math.BigDecimal.valueOf(100)) <= 0,
                        "FLASH_SALE requires discountPercent between 0 and 100");
            }
            case TIERED_DISCOUNT -> require(!configTiers().isEmpty(),
                    "TIERED_DISCOUNT requires at least one tier");
            case BUNDLE -> require(configProductIds().size() >= 2 && configDecimal("bundlePrice", null) != null,
                    "BUNDLE requires at least two productIds and a bundlePrice");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
