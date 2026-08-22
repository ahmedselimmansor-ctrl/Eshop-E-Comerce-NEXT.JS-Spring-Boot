package com.eshop.promotion.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Records that a coupon was actually applied to an order.
 *
 * <p>This table — not {@code coupons.used_count} — is what enforces usage limits. The unique
 * constraint on {@code (coupon_id, order_id)} makes a double-submitted checkout idempotent, and
 * counting rows per user inside the checkout transaction is immune to the lost-update race that a
 * counter increment would have.
 */
@Entity
@Table(name = "coupon_redemptions")
@Getter
@Setter
@NoArgsConstructor
public class CouponRedemption extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    /** {@code null} for a guest checkout, where the per-user limit cannot be enforced. */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    public CouponRedemption(Coupon coupon, UUID userId, UUID orderId, BigDecimal discountAmount) {
        this.coupon = coupon;
        this.userId = userId;
        this.orderId = orderId;
        this.discountAmount = discountAmount;
    }
}
