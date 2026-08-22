package com.eshop.promotion.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.common.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A discount code a customer types at checkout.
 *
 * <p>Every limit here is enforced server-side at order creation, inside the checkout transaction —
 * not when the code is applied to the cart. A code validated against a cart and then applied minutes
 * later at checkout is a race: two concurrent checkouts can both pass a "usage remaining" check
 * that was true when each read it.
 */
@Entity
@Table(name = "coupons")
@Getter
@Setter
@NoArgsConstructor
public class Coupon extends AuditableEntity {

    public enum Type {
        /** A percentage off the eligible subtotal, optionally capped by {@code maxDiscountAmount}. */
        PERCENTAGE,
        /** A flat amount off, never more than the eligible subtotal. */
        FIXED,
        /** Zeroes the shipping charge; the goods subtotal is untouched. */
        FREE_SHIPPING
    }

    public enum AppliesTo {
        ALL,
        CATEGORY,
        PRODUCT,
        BRAND
    }

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "description_ar")
    private String descriptionAr;

    @Column(name = "description_en")
    private String descriptionEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Type type;

    /** A percentage (0–100) for {@link Type#PERCENTAGE}, otherwise a money amount. */
    @Column(name = "value", nullable = false, precision = 12, scale = 2)
    private BigDecimal value;

    @Column(name = "min_order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    /** Ceiling on a percentage discount — "20% off, up to EGP 200". */
    @Column(name = "max_discount_amount", precision = 12, scale = 2)
    private BigDecimal maxDiscountAmount;

    /** Total redemptions allowed across all customers; {@code null} means unlimited. */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "usage_limit_per_user", nullable = false)
    private int usageLimitPerUser = 1;

    /**
     * Denormalised counter for the admin list. Authoritative enforcement counts rows in
     * {@code coupon_redemptions} inside the checkout transaction — this value can lag.
     */
    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 20)
    private AppliesTo appliesTo = AppliesTo.ALL;

    @Column(name = "first_order_only", nullable = false)
    private boolean firstOrderOnly = false;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "coupon", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CouponTarget> targets = new ArrayList<>();

    // ------------------------------------------------------------- behaviour --

    public boolean hasStarted() {
        return startsAt == null || !startsAt.isAfter(Instant.now());
    }

    public boolean hasExpired() {
        return endsAt != null && endsAt.isBefore(Instant.now());
    }

    public boolean isWithinWindow() {
        return hasStarted() && !hasExpired();
    }

    public boolean hasUsesRemaining() {
        return usageLimit == null || usedCount < usageLimit;
    }

    public boolean isFreeShipping() {
        return type == Type.FREE_SHIPPING;
    }

    /**
     * Computes the discount against the portion of the order the coupon actually applies to.
     *
     * <p>{@code eligibleSubtotal} is the sum of the lines this coupon covers, which for a
     * category- or product-scoped coupon is less than the order subtotal. The result is clamped to
     * that eligible amount so a fixed-value coupon can never exceed what was bought — and never
     * turns into a payout.
     */
    public Money discountFor(Money eligibleSubtotal) {
        if (type == Type.FREE_SHIPPING) {
            return Money.zero(eligibleSubtotal.currency());
        }

        Money raw = type == Type.PERCENTAGE
                ? eligibleSubtotal.times(value.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                : Money.of(value, eligibleSubtotal.currency());

        if (maxDiscountAmount != null) {
            raw = raw.min(Money.of(maxDiscountAmount, eligibleSubtotal.currency()));
        }
        return raw.min(eligibleSubtotal).atLeastZero();
    }

    public String description(String locale) {
        return "en".equalsIgnoreCase(locale) && descriptionEn != null ? descriptionEn : descriptionAr;
    }
}
