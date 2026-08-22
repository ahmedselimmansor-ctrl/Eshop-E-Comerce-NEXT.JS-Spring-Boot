package com.eshop.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.eshop.catalog.service.ProductQuery;
import com.eshop.catalog.service.ProductSearchPort;
import com.eshop.config.AppProperties;
import com.eshop.search.domain.ProductDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Elasticsearch-backed implementation of the catalog's search port.
 *
 * <p>Query shape, and why:
 * <ul>
 *   <li><b>Text goes in {@code must}, filters go in {@code filter}.</b> Filter clauses skip scoring
 *       and are cached by Elasticsearch — a category page with no search term costs almost nothing
 *       to re-run.</li>
 *   <li><b>Attribute facets are {@code nested} queries.</b> A flat object mapping would match
 *       "colour = red" on one attribute and "size = large" on another and call it a hit.</li>
 *   <li><b>Facet counts come from aggregations on the same request.</b> One round trip for results
 *       and sidebar counts.</li>
 *   <li><b>Only ids are returned.</b> Cards are hydrated from PostgreSQL so a price edit shows
 *       immediately rather than after the next reindex.</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnBean(ElasticsearchClient.class)
public class ElasticsearchProductSearch implements ProductSearchPort {

    private final ElasticsearchClient client;
    private final ProductIndexer indexer;
    private final String index;

    /**
     * Cached health flag. A cluster that has gone away must not cost every request a connection
     * timeout before falling back — the first failure flips this and the fallback is immediate.
     */
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    public ElasticsearchProductSearch(ElasticsearchClient client,
                                      ProductIndexer indexer,
                                      AppProperties properties) {
        this.client = client;
        this.indexer = indexer;
        this.index = properties.getElasticsearch().getProductIndex();
    }

    @Override
    public boolean isAvailable() {
        return healthy.get();
    }

    // ---------------------------------------------------------------- search --

    @Override
    public SearchHits search(ProductQuery query) {
        try {
            BoolQuery.Builder bool = new BoolQuery.Builder();

            // Only live products are ever visible, regardless of what was asked for.
            bool.filter(term("status", "ACTIVE"));

            if (query.hasTerm()) {
                bool.must(textQuery(query.term(), query.locale()));
            }
            appendFilters(bool, query);

            SearchResponse<ProductDocument> response = client.search(request -> request
                            .index(index)
                            .query(bool.build()._toQuery())
                            .from(query.page() * query.size())
                            .size(query.size())
                            .sort(sortOptions(query))
                            .trackTotalHits(track -> track.enabled(true))
                            .aggregations(aggregations()),
                    ProductDocument.class);

            healthy.set(true);

            List<UUID> ids = response.hits().hits().stream()
                    .map(Hit::id)
                    .map(UUID::fromString)
                    .toList();

            return new SearchHits(ids,
                    response.hits().total() == null ? ids.size() : response.hits().total().value(),
                    extractFacets(response, query.locale()),
                    Map.of());

        } catch (Exception e) {
            healthy.set(false);
            log.warn("elasticsearch_search_failed error={} — catalog will fall back to PostgreSQL",
                    e.toString());
            throw new IllegalStateException("Elasticsearch query failed", e);
        }
    }

    /**
     * Relevance across the fields shoppers actually type into.
     *
     * <p>Field boosts are ordered by intent: a name match beats a brand match beats a description
     * match. {@code fuzziness=AUTO} tolerates the one-character typos that dominate mobile search,
     * and {@code BEST_FIELDS} scores by the single best-matching field rather than summing across
     * them, which stops a long description from outranking an exact title.
     */
    private Query textQuery(String term, String locale) {
        boolean english = "en".equalsIgnoreCase(locale);

        List<String> fields = english
                ? List.of("nameEn^6", "nameAr^3", "brandNameEn^3", "searchKeywords.english^2",
                          "searchKeywords^1", "descriptionEn^1")
                : List.of("nameAr^6", "nameEn^3", "brandNameAr^3", "searchKeywords^2",
                          "searchKeywords.english^1", "descriptionAr^1");

        return Query.of(query -> query.multiMatch(match -> match
                .query(term)
                .fields(fields)
                .type(TextQueryType.BestFields)
                .fuzziness("AUTO")
                .prefixLength(1)
                .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)));
    }

    private void appendFilters(BoolQuery.Builder bool, ProductQuery query) {
        if (query.categoryIds() != null && !query.categoryIds().isEmpty()) {
            bool.filter(terms("categoryIds",
                    query.categoryIds().stream().map(UUID::toString).toList()));
        }
        if (query.brandSlugs() != null && !query.brandSlugs().isEmpty()) {
            bool.filter(terms("brandSlug", query.brandSlugs()));
        }
        if (query.minPrice() != null || query.maxPrice() != null) {
            bool.filter(Query.of(q -> q.range(range -> range.number(number -> {
                number.field("price");
                if (query.minPrice() != null) {
                    number.gte(query.minPrice().doubleValue());
                }
                if (query.maxPrice() != null) {
                    number.lte(query.maxPrice().doubleValue());
                }
                return number;
            }))));
        }
        if (query.minRating() != null) {
            bool.filter(Query.of(q -> q.range(range -> range.number(number -> number
                    .field("ratingAvg")
                    .gte(query.minRating().doubleValue())))));
        }
        if (Boolean.TRUE.equals(query.inStock())) {
            bool.filter(Query.of(q -> q.term(term -> term.field("inStock").value(true))));
        }
        if (Boolean.TRUE.equals(query.onSale())) {
            bool.filter(Query.of(q -> q.term(term -> term.field("onSale").value(true))));
        }

        // Values within one attribute are OR-ed; separate attributes are AND-ed, which is what
        // makes "red or blue, in large" behave the way a shopper expects.
        if (query.attributes() != null) {
            query.attributes().forEach((code, values) -> {
                if (values == null || values.isEmpty()) {
                    return;
                }
                bool.filter(Query.of(q -> q.nested(nested -> nested
                        .path("attributes")
                        .query(inner -> inner.bool(innerBool -> innerBool
                                .filter(term("attributes.code", code))
                                .filter(terms("attributes.valueCode", values)))))));
            });
        }
    }

    private List<co.elastic.clients.elasticsearch._types.SortOptions> sortOptions(ProductQuery query) {
        return switch (query.effectiveSort()) {
            case PRICE_ASC -> List.of(fieldSort("price", SortOrder.Asc));
            case PRICE_DESC -> List.of(fieldSort("price", SortOrder.Desc));
            case NEWEST -> List.of(fieldSort("publishedAt", SortOrder.Desc));
            case BEST_SELLING -> List.of(fieldSort("soldCount", SortOrder.Desc));
            case RATING -> List.of(fieldSort("ratingAvg", SortOrder.Desc),
                    fieldSort("ratingCount", SortOrder.Desc));
            // Relevance first, then sales as the tiebreaker — of two equally relevant products,
            // the one people actually buy is the better answer.
            case RELEVANCE -> List.of(
                    co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s.score(score -> score
                            .order(SortOrder.Desc))),
                    fieldSort("soldCount", SortOrder.Desc));
        };
    }

    private static co.elastic.clients.elasticsearch._types.SortOptions fieldSort(String field, SortOrder order) {
        return co.elastic.clients.elasticsearch._types.SortOptions.of(s -> s.field(f -> f
                .field(field)
                .order(order)
                // Missing values sort last rather than first, so an unpublished-date product does
                // not head the "newest" list.
                .missing(m -> m.stringValue("_last"))));
    }

    // ---------------------------------------------------------------- facets --

    private Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggregations() {
        Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregation> aggregations =
                new LinkedHashMap<>();

        aggregations.put("brands", co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(
                agg -> agg.terms(terms -> terms.field("brandSlug").size(50))));

        aggregations.put("categories", co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(
                agg -> agg.terms(terms -> terms.field("categorySlugs").size(50))));

        aggregations.put("priceRange", co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(
                agg -> agg.stats(stats -> stats.field("price"))));

        // Nested aggregation so attribute code and value stay paired — a flat terms aggregation on
        // valueCode alone would produce "red: 12" with no way to know which attribute it belongs to.
        aggregations.put("attributes", co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(
                agg -> agg.nested(nested -> nested.path("attributes"))
                        .aggregations("codes",
                                co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(
                                        inner -> inner.terms(terms -> terms
                                                        .field("attributes.code").size(30))
                                                .aggregations("values",
                                                        co.elastic.clients.elasticsearch._types.aggregations
                                                                .Aggregation.of(values -> values.terms(
                                                                        terms -> terms
                                                                                .field("attributes.valueCode")
                                                                                .size(50))))))));

        return aggregations;
    }

    private Facets extractFacets(SearchResponse<ProductDocument> response, String locale) {
        List<Bucket> brands = buckets(response, "brands");
        List<Bucket> categories = buckets(response, "categories");

        Map<String, List<Bucket>> attributes = new LinkedHashMap<>();
        try {
            var nested = response.aggregations().get("attributes");
            if (nested != null && nested.isNested()) {
                var codes = nested.nested().aggregations().get("codes");
                if (codes != null && codes.isSterms()) {
                    codes.sterms().buckets().array().forEach(codeBucket -> {
                        var values = codeBucket.aggregations().get("values");
                        if (values != null && values.isSterms()) {
                            attributes.put(codeBucket.key().stringValue(),
                                    values.sterms().buckets().array().stream()
                                            .map(bucket -> new Bucket(bucket.key().stringValue(),
                                                    bucket.key().stringValue(), bucket.docCount()))
                                            .toList());
                        }
                    });
                }
            }
        } catch (RuntimeException e) {
            log.debug("attribute_facet_parse_failed error={}", e.getMessage());
        }

        PriceRange priceRange = new PriceRange(null, null);
        try {
            var stats = response.aggregations().get("priceRange");
            if (stats != null && stats.isStats()) {
                priceRange = new PriceRange(
                        BigDecimal.valueOf(stats.stats().min()),
                        BigDecimal.valueOf(stats.stats().max()));
            }
        } catch (RuntimeException e) {
            log.debug("price_facet_parse_failed error={}", e.getMessage());
        }

        return new Facets(categories, brands, attributes, priceRange);
    }

    private List<Bucket> buckets(SearchResponse<ProductDocument> response, String name) {
        try {
            var aggregation = response.aggregations().get(name);
            if (aggregation == null || !aggregation.isSterms()) {
                return List.of();
            }
            return aggregation.sterms().buckets().array().stream()
                    .map(bucket -> new Bucket(bucket.key().stringValue(),
                            bucket.key().stringValue(), bucket.docCount()))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("facet_parse_failed name={} error={}", name, e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------- suggest --

    @Override
    public Suggestions suggest(String term, String locale, int limit) {
        if (term == null || term.isBlank()) {
            return Suggestions.empty();
        }

        try {
            boolean english = "en".equalsIgnoreCase(locale);
            String primary = english ? "nameEn.autocomplete" : "nameAr.autocomplete";
            String secondary = english ? "nameAr.autocomplete" : "nameEn.autocomplete";

            SearchResponse<ProductDocument> response = client.search(request -> request
                            .index(index)
                            .size(limit)
                            .query(query -> query.bool(bool -> bool
                                    .filter(term("status", "ACTIVE"))
                                    .must(must -> must.multiMatch(match -> match
                                            .query(term)
                                            .fields(primary + "^3", secondary, "searchKeywords")))))
                            // Only the fields the dropdown renders — no descriptions.
                            .source(source -> source.filter(filter -> filter
                                    .includes("id", "slug", "nameAr", "nameEn", "imageUrl", "price"))),
                    ProductDocument.class);

            healthy.set(true);

            List<ProductSuggestion> products = response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .map(document -> new ProductSuggestion(
                            UUID.fromString(document.id()),
                            document.slug(),
                            english && document.nameEn() != null ? document.nameEn() : document.nameAr(),
                            document.imageUrl(),
                            document.price()))
                    .toList();

            return new Suggestions(products, List.of(), List.of(), List.of());

        } catch (Exception e) {
            healthy.set(false);
            log.warn("elasticsearch_suggest_failed error={}", e.toString());
            return Suggestions.empty();
        }
    }

    // ------------------------------------------------------------- indexing --

    @Override
    public void index(UUID productId) {
        indexer.index(productId);
    }

    @Override
    public void delete(UUID productId) {
        indexer.delete(productId);
    }

    @Override
    public long reindexAll() {
        return indexer.reindexAll();
    }

    // -------------------------------------------------------------- helpers --

    private static Query term(String field, String value) {
        return Query.of(query -> query.term(term -> term.field(field).value(value)));
    }

    private static Query terms(String field, List<String> values) {
        List<co.elastic.clients.elasticsearch._types.FieldValue> fieldValues = new ArrayList<>();
        values.forEach(value -> fieldValues.add(
                co.elastic.clients.elasticsearch._types.FieldValue.of(value)));

        return Query.of(query -> query.terms(terms -> terms
                .field(field)
                .terms(builder -> builder.value(fieldValues))));
    }
}
