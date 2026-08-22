package com.eshop.catalog.web;

import com.eshop.catalog.domain.Category;
import com.eshop.catalog.service.CategoryService;
import com.eshop.catalog.service.ProductQuery;
import com.eshop.catalog.service.ProductQueryService;
import com.eshop.catalog.service.ProductSearchPort;
import com.eshop.catalog.service.ProductService;
import com.eshop.catalog.web.dto.ProductCardResponse;
import com.eshop.catalog.web.dto.ProductDetailResponse;
import com.eshop.common.web.PageResponse;
import com.eshop.common.web.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Catalog")
@SecurityRequirements
public class ProductController {

    private static final int MAX_PAGE_SIZE = 60;

    private final ProductQueryService productQuery;
    private final ProductService products;
    private final CategoryService categories;

    @GetMapping
    @Operation(summary = "Filtered product listing",
            description = """
                    Attribute facets are passed as repeated `attr_<code>` parameters, e.g.
                    `?attr_color=red&attr_color=blue&attr_size=l`. Values within one attribute are
                    OR-ed; separate attributes are AND-ed.

                    `facets.searchIndexUsed=false` means the response came from the PostgreSQL
                    fallback and the facet counts are incomplete — hide the affected filters rather
                    than showing numbers that do not add up.
                    """)
    public ResponseEntity<ProductListResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categorySlug,
            @RequestParam(required = false) List<String> brandSlug,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) Boolean onSale,
            @RequestParam(required = false, defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {

        String locale = RequestContext.locale();

        UUID categoryId = null;
        List<UUID> categoryIds = List.of();
        if (categorySlug != null && !categorySlug.isBlank()) {
            Category category = categories.requireBySlug(categorySlug);
            categoryId = category.getId();
            // Expanded here so a listing for a parent category includes everything beneath it.
            categoryIds = categories.selfAndDescendantIds(category);
        }

        ProductQuery query = new ProductQuery(
                q,
                categoryId,
                categoryIds,
                brandSlug == null ? List.of() : brandSlug,
                minPrice,
                maxPrice,
                ProductQueryService.parseAttributeFilters(request.getParameterMap()),
                minRating,
                inStock,
                onSale,
                ProductQuery.Sort.parse(sort),
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                locale);

        ProductQueryService.Result result = productQuery.list(query);
        ProductListResponse body = new ProductListResponse(
                result.page().content(), result.page().page(), result.facets(), result.searchIndexUsed());

        // Only listings that do not depend on live stock or a search term are edge-cacheable.
        return query.isCacheable()
                ? ResponseEntity.ok().cacheControl(listingCache()).body(body)
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Product detail",
            description = "Includes variants, images, specifications, breadcrumb, and live stock. "
                    + "Not edge-cached, because availability changes with every checkout.")
    public ResponseEntity<ProductDetailResponse> detail(@PathVariable String slug) {
        ProductDetailResponse detail = products.detailBySlug(slug, RequestContext.locale());
        products.recordView(detail.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(detail);
    }

    @GetMapping("/{productId}/related")
    @Operation(summary = "Related products")
    public ResponseEntity<List<ProductCardResponse>> related(
            @PathVariable UUID productId,
            @Parameter(description = "1–24") @RequestParam(defaultValue = "12") int limit) {

        List<ProductCardResponse> body = ProductCardResponse.from(
                products.related(productId, Math.clamp(limit, 1, 24)), RequestContext.locale());
        return ResponseEntity.ok().cacheControl(listingCache()).body(body);
    }

    @GetMapping("/{productId}/frequently-bought")
    @Operation(summary = "Frequently bought together",
            description = "Derived from actual order co-occurrence, not category similarity.")
    public ResponseEntity<List<ProductCardResponse>> frequentlyBought(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "4") int limit) {

        List<ProductCardResponse> body = ProductCardResponse.from(
                products.frequentlyBoughtTogether(productId, Math.clamp(limit, 1, 12)),
                RequestContext.locale());
        return ResponseEntity.ok().cacheControl(listingCache()).body(body);
    }

    @GetMapping("/featured")
    @Operation(summary = "Featured products")
    public ResponseEntity<List<ProductCardResponse>> featured(@RequestParam(defaultValue = "12") int limit) {
        return shelf(products.featured(Math.clamp(limit, 1, 40)));
    }

    @GetMapping("/new-arrivals")
    @Operation(summary = "Newest products")
    public ResponseEntity<List<ProductCardResponse>> newArrivals(@RequestParam(defaultValue = "12") int limit) {
        return shelf(products.newArrivals(Math.clamp(limit, 1, 40)));
    }

    @GetMapping("/best-sellers")
    @Operation(summary = "Best selling products")
    public ResponseEntity<List<ProductCardResponse>> bestSellers(@RequestParam(defaultValue = "12") int limit) {
        return shelf(products.bestSellers(Math.clamp(limit, 1, 40)));
    }

    private ResponseEntity<List<ProductCardResponse>> shelf(List<com.eshop.catalog.domain.Product> items) {
        return ResponseEntity.ok()
                .cacheControl(listingCache())
                .body(ProductCardResponse.from(items, RequestContext.locale()));
    }

    private static CacheControl listingCache() {
        return CacheControl.maxAge(Duration.ofMinutes(1))
                .cachePublic()
                .staleWhileRevalidate(Duration.ofMinutes(5));
    }

    /** Listing payload: the page plus the filter sidebar's facet counts. */
    public record ProductListResponse(List<ProductCardResponse> content,
                                      PageResponse.PageMeta page,
                                      ProductSearchPort.Facets facets,
                                      boolean searchIndexUsed) {
    }
}
