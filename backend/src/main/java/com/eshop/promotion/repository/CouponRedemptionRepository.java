package com.eshop.promotion.repository;

import com.eshop.promotion.domain.CouponRedemption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    /** Authoritative per-user usage count — see {@link CouponRedemption}. */
    @Query("SELECT COUNT(r) FROM CouponRedemption r WHERE r.coupon.id = :couponId AND r.userId = :userId")
    long countForUser(@Param("couponId") UUID couponId, @Param("userId") UUID userId);

    @Query("SELECT COUNT(r) FROM CouponRedemption r WHERE r.coupon.id = :couponId")
    long countTotal(@Param("couponId") UUID couponId);

    Page<CouponRedemption> findByCouponIdOrderByCreatedAtDesc(UUID couponId, Pageable pageable);

    List<CouponRedemption> findByOrderId(UUID orderId);

    /** True when this customer has never completed an order — gates {@code firstOrderOnly} coupons. */
    @Query(value = """
            SELECT NOT EXISTS (
                SELECT 1 FROM orders
                 WHERE user_id = :userId
                   AND status NOT IN ('CANCELLED', 'FAILED', 'PENDING_PAYMENT')
            )
            """, nativeQuery = true)
    boolean isFirstOrder(@Param("userId") UUID userId);
}
