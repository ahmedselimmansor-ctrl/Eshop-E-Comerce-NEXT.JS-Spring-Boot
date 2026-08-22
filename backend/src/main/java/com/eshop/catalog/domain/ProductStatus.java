package com.eshop.catalog.domain;

/** Publication state of a product. */
public enum ProductStatus {

    /** Being prepared. Invisible to the storefront and excluded from the search index. */
    DRAFT,

    /** Live and purchasable. */
    ACTIVE,

    /**
     * Withdrawn from sale but retained. Existing orders keep resolving their product reference, and
     * the slug stays claimed so an old link does not silently start pointing at a different item.
     */
    ARCHIVED
}
