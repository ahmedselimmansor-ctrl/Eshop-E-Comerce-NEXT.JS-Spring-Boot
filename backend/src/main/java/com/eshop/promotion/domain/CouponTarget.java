package com.eshop.promotion.domain;

import com.eshop.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Narrows a coupon to specific catalog entities.
 *
 * <p>The target is a bare id plus a type rather than three nullable foreign keys, so adding a new
 * scope later does not require a schema change. Referential integrity is traded for that
 * flexibility deliberately - a deleted target simply stops matching, which is the desired behaviour
 * anyway.
 */
@Entity
@Table(name = "coupon_targets")
@Getter
@Setter
@NoArgsConstructor
public class CouponTarget extends BaseEntity {

    public enum TargetType {
        CATEGORY,
        PRODUCT,
        BRAND
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    public CouponTarget(Coupon coupon, TargetType targetType, UUID targetId) {
        this.coupon = coupon;
        this.targetType = targetType;
        this.targetId = targetId;
    }
}
