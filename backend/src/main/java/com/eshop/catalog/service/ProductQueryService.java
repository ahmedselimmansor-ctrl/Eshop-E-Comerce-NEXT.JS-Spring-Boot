package com.eshop.catalog.service;

import com.eshop.catalog.domain.Product;
import com.eshop.catalog.repository.ProductRepository;
import com.eshop.catalog.repository.ProductRepository.BrandFacet;
import com.eshop.catalog.repository.ProductSpecifications;
import com.eshop.catalog.web.dto.ProductCardResponse;
import com.eshop.common.web.PageResponse;
import com.eshop.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Runs product listings, preferring Elasticsearch and falling back to PostgreSQL.
 *
 * <p>The fallback is not a nicety. Search is the one dependency whose loss would otherwise take the
 * whole storefront down - category pages, brand pages, and the home page all go through here. A
 * degraded listing (no attribute facets, simpler relevance) is a far better outcome than a 503 on
 * every page.
 *
 * <p>In both paths Elasticsearch only ever returns <em>ids</em>. Cards are hydrated from
 * PostgreSQL, so a price edit is visible immediately even if the index has not caught up.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductQueryService {

    private final ProductRepository products;
    private final InventoryService inventory;

    /**
     * Optional so the application still starts with {@code eshop.elasticsearch.enabled=false},
     * where no implementation of the port is registered at all.
     */
    private final ObjectProvider<ProductSearchPort> searchPort;

    @Transactional(readOnly = true)
    public Result list(ProductQuery query) {
        ProductSearchPort search = searchPort.getIfAvailable();

        if (search != null && search.isAvailable()) {
            try {
                return viaSearchIndex(search, query);
            } catch (RuntimeException e) {
                // Never surface a search outage as a failed page load.
                log.warn("search_query_failed falling_back_to_database error={}", e.toString());
            }
        }
        return viaDatabase(query);
    }

    // ------------------------------------------------------- elasticsearch --

    private Result viaSearchIndex(ProductSearchPort search, ProductQuery query) {
        ProductSearchPort.SearchHits hits = search.search(query);

        if (hits.isEmpty()) {
            return new Result(PageResponse.of(List.of(), query.page(), query.size(), hits.totalHits()),
                    hits.facets(), true);
        }

        // findCardsByIds returns rows in whatever order the database chooses; the index already
        // ranked them, so the original ordering is reapplied here.
        Map<UUID, Product> byId = products.findCardsByIds(hits.productIds()).stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        List<Product> ordered = hits.productIds().stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();

        List<ProductCardResponse> cards = toCards(ordered, query.locale());

        return new Result(
                PageResponse.of(cards, query.page(), query.size(), hits.totalHits()),
                hits.facets(),
                true);
    }

    // ------------------------------------------------------------ database --

    private Result viaDatabase(ProductQuery query) {
        Specification<Product> spec = ProductSpecifications.visible();

        spec = and(spec, ProductSpecifications.inCategories(query.categoryIds()));
        spec = and(spec, ProductSpecifications.brandSlugIn(query.brandSlugs()));
        spec = and(spec, ProductSpecifications.priceBetween(query.minPrice(), query.maxPrice()));
        spec = and(spec, ProductSpecifications.withAttributes(query.attributes()));
        spec = and(spec, ProductSpecifications.minRating(query.minRating()));
        spec = and(spec, ProductSpecifications.onSale(query.onSale()));
        spec = and(spec, ProductSpecifications.inStock(query.inStock()));
        spec = and(spec, ProductSpecifications.nameContains(query.term()));

        Page<Product> page = products.findAll(spec, pageable(query));
        List<ProductCardResponse> cards = toCards(page.getContent(), query.locale());

        return new Result(
                new PageResponse<>(cards, new PageResponse.PageMeta(
                        page.getNumber(), page.getSize(), page.getTotalElements(),
                        page.getTotalPages(), page.isFirst(), page.isLast())),
                degradedFacets(query),
                false);
    }

    /**
     * Facets for the fallback path.
     *
     * <p>Brand counts and the price range are scoped to the category but ignore the other active
     * filters, and attribute facets are omitted entirely. Computing them correctly means one
     * aggregate query per facet with that facet's own filter removed - six or more round trips per
     * listing, on the path that is already running because the fast path is down. The response
     * carries {@code searchIndexUsed=false} so the frontend can hide filters whose counts it cannot
     * trust rather than display numbers that do not add up.
     */
    private ProductSearchPort.Facets degradedFacets(ProductQuery query) {
        List<UUID> categoryIds = query.categoryIds() == null || query.categoryIds().isEmpty()
                ? null : query.categoryIds();

        List<BrandFacet> rawBrands = categoryIds == null
                ? products.brandFacets()
                : products.brandFacetsInCategories(categoryIds);

        List<ProductSearchPort.Bucket> brands = rawBrands.stream()
                .map(facet -> new ProductSearchPort.Bucket(
                        facet.getSlug(),
                        "en".equalsIgnoreCase(query.locale()) && facet.getNameEn() != null
                                ? facet.getNameEn() : facet.getNameAr(),
                        facet.getTotal()))
                .toList();

        var range = categoryIds == null
                ? products.priceRangeFacet()
                : products.priceRangeFacetInCategories(categoryIds);

        return new ProductSearchPort.Facets(
                List.of(),
                brands,
                Map.of(),
                new ProductSearchPort.PriceRange(
                        range == null ? null : range.getMinPrice(),
                        range == null ? null : range.getMaxPrice()));
    }

    // --------------------------------------------------------------- shared --

    /**
     * Builds cards, attaching availability in one bulk lookup.
     *
     * <p>Availability is resolved here rather than inside {@code ProductCardResponse} so that
     * twenty cards cost one inventory query, not twenty.
     */
    private List<ProductCardResponse> toCards(List<Product> items, String locale) {
        if (items.isEmpty()) {
            return List.of();
        }

        List<InventoryService.SkuRef> refs = items.stream()
                .map(product -> new InventoryService.SkuRef(product.getId(), null))
                .toList();

        Map<InventoryService.SkuRef, Integer> availability = inventory.availabilityFor(refs);

        return items.stream()
                .map(product -> ProductCardResponse.from(product, locale,
                        availability.getOrDefault(
                                new InventoryService.SkuRef(product.getId(), null), 0) > 0))
                .toList();
    }

    private Pageable pageable(ProductQuery query) {
        Sort sort = switch (query.effectiveSort()) {
            case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "basePrice");
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "basePrice");
            case BEST_SELLING -> Sort.by(Sort.Direction.DESC, "soldCount");
            case RATING -> Sort.by(Sort.Direction.DESC, "ratingAvg", "ratingCount");
            // NEWEST and the degraded RELEVANCE both order by publication date. The secondary id
            // key makes the order total, so paging cannot show the same row on two pages.
            default -> Sort.by(Sort.Direction.DESC, "publishedAt").and(Sort.by(Sort.Direction.DESC, "id"));
        };
        return PageRequest.of(query.page(), query.size(), sort);
    }

    private static Specification<Product> and(Specification<Product> base, Specification<Product> addition) {
        return addition == null ? base : base.and(addition);
    }

    /** Attribute filters parsed from {@code ?attr_color=red&attr_color=blue&attr_size=l}. */
    public static Map<String, List<String>> parseAttributeFilters(Map<String, String[]> parameters) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        parameters.forEach((name, values) -> {
            if (name.startsWith("attr_") && values != null && values.length > 0) {
                String code = name.substring("attr_".length());
                if (!code.isBlank()) {
                    filters.put(code, List.of(values));
                }
            }
        });
        return filters;
    }

    /**
     * @param searchIndexUsed {@code false} when the listing came from the PostgreSQL fallback, in
     *                        which case the facets are incomplete
     */
    public record Result(PageResponse<ProductCardResponse> page,
                         ProductSearchPort.Facets facets,
                         boolean searchIndexUsed) {
    }
}
