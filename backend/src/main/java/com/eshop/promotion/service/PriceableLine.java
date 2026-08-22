package com.eshop.promotion.service;

import com.eshop.common.money.Money;

import java.util.Set;
import java.util.UUID;

/**
 * One order line reduced to just what the discount engine needs to decide eligibility.
 *
 * <p>Deliberately not a {@code CartItem} or an {@code OrderItem}: the same rules have to run
 * against a cart preview, a checkout quote, and the order itself, and coupling the engine to any
 * one of those would force the other two to fake it.
 *
 * @param categoryIds the line's primary category and all of its ancestors, so a coupon scoped to
 *                    "Electronics" also matches a product filed under "Electronics / Phones"
 */
public record PriceableLine(
        UUID productId,
        UUID variantId,
        Set<UUID> categoryIds,
        UUID brandId,
        Money unitPrice,
        int quantity
) {

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
