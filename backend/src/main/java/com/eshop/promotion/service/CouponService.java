package com.eshop.promotion.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.money.Money;
import com.eshop.promotion.domain.Coupon;
import com.eshop.promotion.domain.CouponRedemption;
import com.eshop.promotion.domain.CouponTarget;
import com.eshop.promotion.repository.CouponRedemptionRepository;
import com.eshop.promotion.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Validates and redeems discount codes.
 *
 * <p>Validation happens twice, on purpose:
 * <ol>
 *   <li>When the shopper applies the code to their cart - cheap, unlocked, purely advisory, so the
 *       UI can show the discount immediately.</li>
 *   <li>Again inside the checkout transaction, with the coupon row locked - authoritative.</li>
 * </ol>
 * Only the second one counts. Between the two, another customer can exhaust the last use, the code
 * can be deactivated, or the cart contents can change. Trusting the first would let a coupon exceed
 * its limit under concurrency, and "we gave away 400 uses of a 100-use code" is not a recoverable
 * mistake.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;

    /**
     * Advisory check for the cart screen. Never throws for a rejected coupon - it returns the
     * reason so the UI can explain it rather than showing a generic error.
     */
    @Transactional(readOnly = true)
    public Evaluation evaluate(String rawCode, List<PriceableLine> lines, UUID userId) {
        String code = normalize(rawCode);

        return coupons.findByCode(code)
                .map(coupon -> check(coupon, lines, userId, false))
                .orElseGet(() -> Evaluation.rejected(code, ErrorCode.COUPON_INVALID));
    }

    /**
     * Authoritative validation, run inside the checkout transaction with the coupon row locked.
     *
     * @throws BusinessException with the specific reason, so the customer is told which limit they
     *                           hit rather than "invalid coupon"
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Evaluation validateForCheckout(String rawCode, List<PriceableLine> lines, UUID userId) {
        String code = normalize(rawCode);

        Coupon coupon = coupons.lockByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_INVALID, "Unknown coupon " + code));

        Evaluation evaluation = check(coupon, lines, userId, true);
        if (!evaluation.valid()) {
            throw new BusinessException(evaluation.reason(), "Coupon rejected: " + evaluation.reason());
        }
        return evaluation;
    }

    /**
     * Records the redemption. Must run in the checkout transaction so the coupon cannot be counted
     * as used for an order that then fails to commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void redeem(UUID couponId, UUID userId, UUID orderId, Money discount) {
        Coupon coupon = coupons.findById(couponId)
                .orElseThrow(() -> NotFoundException.of("Coupon", couponId));

        redemptions.save(new CouponRedemption(coupon, userId, orderId, discount.amount()));
        coupons.incrementUsedCount(couponId);

        log.info("coupon_redeemed code={} order={} user={} discount={}",
                coupon.getCode(), orderId, userId, discount);
    }

    /** Reverses a redemption when its order is cancelled before payment. */
    @Transactional
    public void releaseForOrder(UUID orderId) {
        List<CouponRedemption> released = redemptions.findByOrderId(orderId);
        released.forEach(redemption -> {
            Coupon coupon = redemption.getCoupon();
            coupon.setUsedCount(Math.max(0, coupon.getUsedCount() - 1));
            coupons.save(coupon);
        });
        redemptions.deleteAll(released);

        if (!released.isEmpty()) {
            log.info("coupon_redemptions_released order={} count={}", orderId, released.size());
        }
    }

    // ------------------------------------------------------------ validation --

    private Evaluation check(Coupon coupon, List<PriceableLine> lines, UUID userId, boolean authoritative) {
        String code = coupon.getCode();

        if (!coupon.isActive()) {
            return Evaluation.rejected(code, ErrorCode.COUPON_INVALID);
        }
        if (!coupon.hasStarted()) {
            return Evaluation.rejected(code, ErrorCode.COUPON_NOT_STARTED);
        }
        if (coupon.hasExpired()) {
            return Evaluation.rejected(code, ErrorCode.COUPON_EXPIRED);
        }

        // Under the lock, count rows rather than trusting the denormalised counter.
        boolean exhausted = authoritative
                ? coupon.getUsageLimit() != null && redemptions.countTotal(coupon.getId()) >= coupon.getUsageLimit()
                : !coupon.hasUsesRemaining();
        if (exhausted) {
            return Evaluation.rejected(code, ErrorCode.COUPON_USAGE_EXCEEDED);
        }

        if (userId != null && coupon.getUsageLimitPerUser() > 0
                && redemptions.countForUser(coupon.getId(), userId) >= coupon.getUsageLimitPerUser()) {
            return Evaluation.rejected(code, ErrorCode.COUPON_USAGE_EXCEEDED);
        }

        if (coupon.isFirstOrderOnly()) {
            // A guest cannot be shown to be a first-time buyer, so the restriction rejects them.
            if (userId == null || !redemptions.isFirstOrder(userId)) {
                return Evaluation.rejected(code, ErrorCode.COUPON_NOT_APPLICABLE);
            }
        }

        List<PriceableLine> eligible = eligibleLines(coupon, lines);
        if (eligible.isEmpty()) {
            return Evaluation.rejected(code, ErrorCode.COUPON_NOT_APPLICABLE);
        }

        Money orderSubtotal = total(lines);
        if (coupon.getMinOrderAmount().signum() > 0
                && orderSubtotal.amount().compareTo(coupon.getMinOrderAmount()) < 0) {
            return Evaluation.rejected(code, ErrorCode.COUPON_MIN_ORDER);
        }

        Money eligibleSubtotal = total(eligible);
        Money discount = coupon.discountFor(eligibleSubtotal);

        return new Evaluation(true, coupon.getId(), code, coupon.getType(), discount,
                coupon.isFreeShipping(), null);
    }

    /**
     * The subset of lines a scoped coupon covers.
     *
     * <p>A category-scoped coupon matches against the line's full ancestor set, which the caller
     * supplies - otherwise a coupon for "Electronics" would silently miss every product filed only
     * under a sub-category.
     */
    private List<PriceableLine> eligibleLines(Coupon coupon, List<PriceableLine> lines) {
        if (coupon.getAppliesTo() == Coupon.AppliesTo.ALL) {
            return lines;
        }

        Set<UUID> targets = coupon.getTargets().stream()
                .filter(target -> matchesScope(coupon.getAppliesTo(), target.getTargetType()))
                .map(CouponTarget::getTargetId)
                .collect(Collectors.toSet());

        if (targets.isEmpty()) {
            // A scoped coupon with no targets matches nothing - safer than falling back to "all".
            return List.of();
        }

        return lines.stream().filter(line -> switch (coupon.getAppliesTo()) {
            case PRODUCT -> targets.contains(line.productId());
            case BRAND -> line.brandId() != null && targets.contains(line.brandId());
            case CATEGORY -> line.categoryIds().stream().anyMatch(targets::contains);
            case ALL -> true;
        }).toList();
    }

    private static boolean matchesScope(Coupon.AppliesTo appliesTo, CouponTarget.TargetType targetType) {
        return switch (appliesTo) {
            case PRODUCT -> targetType == CouponTarget.TargetType.PRODUCT;
            case BRAND -> targetType == CouponTarget.TargetType.BRAND;
            case CATEGORY -> targetType == CouponTarget.TargetType.CATEGORY;
            case ALL -> true;
        };
    }

    private static Money total(List<PriceableLine> lines) {
        return lines.stream()
                .map(PriceableLine::lineTotal)
                .reduce(Money.zero(), Money::plus);
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Outcome of a coupon check.
     *
     * @param freeShipping when true, the caller zeroes the shipping charge instead of, or in
     *                     addition to, subtracting {@code discount}
     * @param reason       populated only when {@code valid} is false
     */
    public record Evaluation(boolean valid,
                             UUID couponId,
                             String code,
                             Coupon.Type type,
                             Money discount,
                             boolean freeShipping,
                             ErrorCode reason) {

        static Evaluation rejected(String code, ErrorCode reason) {
            return new Evaluation(false, null, code, null, Money.zero(), false, reason);
        }

        public static Evaluation none() {
            return new Evaluation(false, null, null, null, Money.zero(), false, null);
        }
    }
}
