package com.eshop.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A parsed product listing request — the single shape both the Elasticsearch path and the
 * PostgreSQL fallback consume.
 *
 * @param term            free-text query; {@code null} for a plain category or brand listing
 * @param categoryId      the requested category, already resolved from its slug
 * @param categoryIds     that category plus every descendant, pre-expanded by the caller
 * @param brandSlugs      OR-ed together
 * @param attributes      {@code attributeCode → allowed value codes}; values within one attribute
 *                        are OR-ed, separate attributes are AND-ed
 * @param minRating       minimum average rating
 * @param inStock         restrict to SKUs with sellable stock
 * @param onSale          restrict to discounted products
 * @param sort            see {@link Sort}
 * @param page            zero-based
 * @param size            capped at 100 by {@code WebConfig}
 * @param locale          {@code ar} or {@code en}; selects the analysed text field in Elasticsearch
 */
public record ProductQuery(
        String term,
        UUID categoryId,
        List<UUID> categoryIds,
        List<String> brandSlugs,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Map<String, List<String>> attributes,
        BigDecimal minRating,
        Boolean inStock,
        Boolean onSale,
        Sort sort,
        int page,
        int size,
        String locale
) {

    public enum Sort {
        /**
         * Text relevance when a term is present. With no term there is nothing to score, so this
         * degrades to {@link #NEWEST} rather than returning an arbitrary order.
         */
        RELEVANCE,
        PRICE_ASC,
        PRICE_DESC,
        NEWEST,
        BEST_SELLING,
        RATING;

        public static Sort parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return RELEVANCE;
            }
            return switch (raw.toLowerCase()) {
                case "price_asc" -> PRICE_ASC;
                case "price_desc" -> PRICE_DESC;
                case "newest" -> NEWEST;
                case "best_selling" -> BEST_SELLING;
                case "rating" -> RATING;
                default -> RELEVANCE;
            };
        }
    }

    public boolean hasTerm() {
        return term != null && !term.isBlank();
    }

    /** Effective ordering, resolving RELEVANCE with no search term to NEWEST. */
    public Sort effectiveSort() {
        return sort == Sort.RELEVANCE && !hasTerm() ? Sort.NEWEST : sort;
    }

    /**
     * Whether the result is safe to cache publicly.
     *
     * <p>Stock-filtered listings are excluded: availability changes with every checkout, and a CDN
     * entry that outlives it would advertise sold-out items as in stock.
     */
    public boolean isCacheable() {
        return !Boolean.TRUE.equals(inStock) && !hasTerm();
    }
}
