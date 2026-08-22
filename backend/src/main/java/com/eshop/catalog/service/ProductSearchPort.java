package com.eshop.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the catalog needs from a search engine, expressed as an interface the catalog itself owns.
 *
 * <p>The implementation ({@code ElasticsearchProductSearch}) lives in {@code com.eshop.search} and
 * depends on catalog entities in order to build documents. Declaring the port here rather than
 * importing the search service directly keeps that dependency pointing one way — without it the two
 * packages would import each other, which is the point at which "modular monolith" stops meaning
 * anything.
 *
 * <p>Every method must degrade rather than throw when the cluster is unavailable:
 * {@link #isAvailable()} lets the caller fall back to PostgreSQL instead of failing the request.
 */
public interface ProductSearchPort {

    /** Whether search is configured and the cluster answered its last health check. */
    boolean isAvailable();

    /**
     * Runs a listing query.
     *
     * <p>Returns product ids rather than documents: the index is a search structure, not the source
     * of truth, and rendering cards from it would show stale prices for as long as indexing lags.
     * The caller hydrates the ids from PostgreSQL.
     */
    SearchHits search(ProductQuery query);

    /** Type-ahead suggestions for the search box. */
    Suggestions suggest(String term, String locale, int limit);

    /** Upserts one product document. Called from the outbox relay, never inline with a write. */
    void index(UUID productId);

    void delete(UUID productId);

    /** Full rebuild, used after a mapping change or to repair drift. */
    long reindexAll();

    /**
     * @param productIds   matching ids, in result order
     * @param totalHits    total matches, for pagination
     * @param facets       aggregation counts for the filter sidebar
     * @param highlights   {@code productId → highlighted snippet}, when a term was supplied
     */
    record SearchHits(List<UUID> productIds,
                      long totalHits,
                      Facets facets,
                      Map<UUID, String> highlights) {

        public static SearchHits empty() {
            return new SearchHits(List.of(), 0, Facets.empty(), Map.of());
        }

        public boolean isEmpty() {
            return productIds.isEmpty();
        }
    }

    /**
     * Filter sidebar counts.
     *
     * <p>Counts are computed against the query with that facet's own filter removed, so selecting
     * "Red" does not collapse every other colour to zero and strand the user with no way back.
     */
    record Facets(List<Bucket> categories,
                  List<Bucket> brands,
                  Map<String, List<Bucket>> attributes,
                  PriceRange priceRange) {

        public static Facets empty() {
            return new Facets(List.of(), List.of(), Map.of(), new PriceRange(null, null));
        }
    }

    record Bucket(String key, String label, long count) {
    }

    record PriceRange(BigDecimal min, BigDecimal max) {
    }

    record Suggestions(List<ProductSuggestion> products,
                       List<Bucket> categories,
                       List<Bucket> brands,
                       List<String> queries) {

        public static Suggestions empty() {
            return new Suggestions(List.of(), List.of(), List.of(), List.of());
        }
    }

    record ProductSuggestion(UUID id, String slug, String name, String imageUrl, BigDecimal price) {
    }
}
