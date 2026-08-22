package com.eshop.catalog.domain;

/** Whether a product is a single purchasable unit or a family of variants. */
public enum ProductType {

    /** One SKU. Price and stock live on the product row itself. */
    SIMPLE,

    /**
     * Several purchasable {@code ProductVariant}s along one or more attribute axes. The product's
     * {@code basePrice} is only a display anchor ("from EGP 1,299"); the amount actually charged
     * always comes from the selected variant.
     */
    VARIABLE
}
