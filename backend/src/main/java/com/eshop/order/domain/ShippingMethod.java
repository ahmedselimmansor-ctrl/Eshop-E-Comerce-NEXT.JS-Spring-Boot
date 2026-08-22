package com.eshop.order.domain;

import com.eshop.common.domain.AuditableEntity;
import com.eshop.common.money.Money;
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
import java.math.RoundingMode;

/**
 * A delivery option, priced for one {@link ShippingZone}.
 *
 * <p>The rate is a base charge plus an optional per-kilogram component, with a free-shipping
 * threshold on order value. Weight is billed in whole kilograms rounded up, which is how couriers
 * here actually price it.
 */
@Entity
@Table(name = "shipping_methods")
@Getter
@Setter
@NoArgsConstructor
public class ShippingMethod extends AuditableEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name_ar", nullable = false, length = 120)
    private String nameAr;

    @Column(name = "name_en", length = 120)
    private String nameEn;

    @Column(name = "description_ar")
    private String descriptionAr;

    @Column(name = "description_en")
    private String descriptionEn;

    /** {@code null} means the method is available everywhere. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private ShippingZone zone;

    @Column(name = "base_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseCost = BigDecimal.ZERO;

    @Column(name = "cost_per_kg", nullable = false, precision = 12, scale = 2)
    private BigDecimal costPerKg = BigDecimal.ZERO;

    /** Order subtotal at or above which shipping is free; {@code null} disables the promotion. */
    @Column(name = "free_shipping_threshold", precision = 12, scale = 2)
    private BigDecimal freeShippingThreshold;

    @Column(name = "estimated_days_min")
    private Short estimatedDaysMin;

    @Column(name = "estimated_days_max")
    private Short estimatedDaysMax;

    /**
     * Whether cash on delivery is offered with this method. Same-day couriers generally will not
     * carry cash, so the flag is per method rather than store-wide.
     */
    @Column(name = "supports_cod", nullable = false)
    private boolean supportsCod = true;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "position", nullable = false)
    private int position = 0;

    // ------------------------------------------------------------- behaviour --

    public boolean servesGovernorate(String governorate) {
        return active && (zone == null || zone.covers(governorate));
    }

    /**
     * Cost for an order.
     *
     * @param subtotal    goods value, compared against the free-shipping threshold
     * @param totalGrams  combined shipping weight; billed per whole kilogram, rounded up
     */
    public Money costFor(Money subtotal, int totalGrams) {
        String currency = subtotal.currency();

        if (freeShippingThreshold != null
                && subtotal.amount().compareTo(freeShippingThreshold) >= 0) {
            return Money.zero(currency);
        }

        Money cost = Money.of(baseCost, currency);

        if (costPerKg.signum() > 0 && totalGrams > 0) {
            BigDecimal kilograms = BigDecimal.valueOf(totalGrams)
                    .divide(BigDecimal.valueOf(1000), 0, RoundingMode.CEILING);
            cost = cost.plus(Money.of(costPerKg.multiply(kilograms), currency));
        }
        return cost;
    }

    public String name(String locale) {
        return "en".equalsIgnoreCase(locale) && nameEn != null && !nameEn.isBlank() ? nameEn : nameAr;
    }

    public String description(String locale) {
        return "en".equalsIgnoreCase(locale) && descriptionEn != null ? descriptionEn : descriptionAr;
    }
}
