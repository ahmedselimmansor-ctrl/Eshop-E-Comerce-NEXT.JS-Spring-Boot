package com.eshop.order.service;

import com.eshop.common.exception.BusinessException;
import com.eshop.common.exception.ErrorCode;
import com.eshop.common.exception.NotFoundException;
import com.eshop.common.money.Money;
import com.eshop.config.RedisConfig;
import com.eshop.order.domain.ShippingMethod;
import com.eshop.order.repository.ShippingMethodRepository;
import com.eshop.order.repository.ShippingZoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Resolves which delivery options serve an address and what each one costs.
 *
 * <p>Availability is decided by the destination governorate, matched against the JSONB governorate
 * list on each {@code ShippingZone}. A method with no zone is nationwide.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingService {

    private final ShippingMethodRepository methods;
    private final ShippingZoneRepository zones;

    /**
     * Priced options for a destination.
     *
     * <p>Returns an empty list rather than throwing when nowhere serves the address — the checkout
     * UI needs to say "we do not deliver here yet", which is a different message from an error.
     */
    @Transactional(readOnly = true)
    public List<Quote> quotesFor(String governorate, Money subtotal, int totalGrams) {
        if (governorate == null || governorate.isBlank()) {
            return List.of();
        }

        return methods.findServing(governorate.trim()).stream()
                .map(method -> new Quote(
                        method.getId(),
                        method.getCode(),
                        method.getNameAr(),
                        method.getNameEn(),
                        method.getDescriptionAr(),
                        method.getDescriptionEn(),
                        method.costFor(subtotal, totalGrams),
                        method.getEstimatedDaysMin(),
                        method.getEstimatedDaysMax(),
                        method.isSupportsCod(),
                        // Surfaced so the UI can nudge: "spend EGP 120 more for free delivery".
                        method.getFreeShippingThreshold() == null
                                ? null
                                : Money.of(method.getFreeShippingThreshold(), subtotal.currency())))
                .toList();
    }

    /**
     * Prices one chosen method, re-checking that it actually serves the destination.
     *
     * <p>Re-checked rather than trusted: the client picked the method from a list produced for a
     * possibly different address, and a method id is not proof of eligibility.
     *
     * @throws BusinessException {@code SHIPPING_UNAVAILABLE} when the method does not serve the area
     */
    @Transactional(readOnly = true)
    public PricedMethod priceFor(UUID methodId, String governorate, Money subtotal, int totalGrams) {
        ShippingMethod method = methods.findById(methodId)
                .orElseThrow(() -> NotFoundException.of("ShippingMethod", methodId));

        if (!method.servesGovernorate(governorate)) {
            throw new BusinessException(ErrorCode.SHIPPING_UNAVAILABLE,
                    "Method " + method.getCode() + " does not serve " + governorate)
                    .withViolation("shippingMethodId", "NOT_SERVED",
                            "طريقة الشحن غير متاحة لهذه المنطقة", methodId);
        }

        return new PricedMethod(method, method.costFor(subtotal, totalGrams));
    }

    @Transactional(readOnly = true)
    public ShippingMethod require(UUID methodId) {
        return methods.findById(methodId)
                .orElseThrow(() -> NotFoundException.of("ShippingMethod", methodId));
    }

    @Cacheable(value = RedisConfig.CACHE_SHIPPING_METHODS, key = "'all'")
    @Transactional(readOnly = true)
    public List<ShippingMethod> listActive() {
        return methods.findByActiveTrueOrderByPositionAsc();
    }

    @Transactional(readOnly = true)
    public boolean isServiceable(String governorate) {
        return governorate != null
                && !governorate.isBlank()
                && !methods.findServing(governorate.trim()).isEmpty();
    }

    @Transactional(readOnly = true)
    public List<com.eshop.order.domain.ShippingZone> listZones() {
        return zones.findByActiveTrueOrderByNameArAsc();
    }

    @CacheEvict(value = RedisConfig.CACHE_SHIPPING_METHODS, allEntries = true)
    public void evictCache() {
        // The annotation is the behaviour; called after any shipping method or zone write.
    }

    /**
     * A delivery option as the checkout screen shows it.
     *
     * @param freeShippingThreshold order value at which this method becomes free, or {@code null}
     */
    public record Quote(UUID methodId,
                        String code,
                        String nameAr,
                        String nameEn,
                        String descriptionAr,
                        String descriptionEn,
                        Money cost,
                        Short estimatedDaysMin,
                        Short estimatedDaysMax,
                        boolean supportsCod,
                        Money freeShippingThreshold) {

        public String name(String locale) {
            return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
        }

        public String description(String locale) {
            return "en".equalsIgnoreCase(locale) && descriptionEn != null ? descriptionEn : descriptionAr;
        }

        public boolean isFree() {
            return cost.isZero();
        }
    }

    /** The validated method plus its price, ready to be frozen onto an order. */
    public record PricedMethod(ShippingMethod method, Money cost) {
    }
}
