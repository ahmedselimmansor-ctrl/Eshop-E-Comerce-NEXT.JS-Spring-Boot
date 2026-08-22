package com.eshop.search.web;

import com.eshop.catalog.service.ProductQuery;
import com.eshop.catalog.service.ProductQueryService;
import com.eshop.catalog.service.ProductSearchPort;
import com.eshop.catalog.web.dto.ProductCardResponse;
import com.eshop.common.web.PageResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.search.service.SearchAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Tag(name = "Search")
@SecurityRequirements
public class SearchController {

    private final ProductQueryService productQuery;
    private final SearchAnalyticsService analytics;
    private final ObjectProvider<ProductSearchPort> searchPort;

    @GetMapping
    @Operation(summary = "Full-text product search",
            description = "Same response shape as GET /products. Falls back to PostgreSQL trigram "
                    + "matching when the search cluster is unavailable, with `searchIndexUsed=false`.")
    public ResponseEntity<SearchResponse> search(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(required = false) List<String> brandSlug,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {

        String locale = RequestContext.locale();

        ProductQuery query = new ProductQuery(
                q, null, List.of(),
                brandSlug == null ? List.of() : brandSlug,
                minPrice, maxPrice,
                ProductQueryService.parseAttributeFilters(request.getParameterMap()),
                null, inStock, null,
                ProductQuery.Sort.parse(sort),
                Math.max(0, page), Math.clamp(size, 1, 60), locale);

        ProductQueryService.Result result = productQuery.list(query);

        // Recorded asynchronously — the "popular searches" list and future relevance tuning both
        // depend on this, but nothing about the response should wait for a write.
        analytics.recordQuery(q, locale, result.page().page().totalElements());

        SearchResponse body = new SearchResponse(
                q,
                result.page().content(),
                result.page().page(),
                result.facets(),
                result.searchIndexUsed());

        // A search response is personal enough (and volatile enough) not to share at the edge.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/suggest")
    @Operation(summary = "Type-ahead suggestions",
            description = "Edge-n-gram matched, Arabic letter forms normalised so "
                    + "'احمد' and 'أحمد' behave identically. Returns an empty list rather than an "
                    + "error when search is unavailable — a dead dropdown is better than a broken page.")
    public ResponseEntity<ProductSearchPort.Suggestions> suggest(
            @RequestParam @NotBlank @Size(max = 100) String q,
            @RequestParam(defaultValue = "8") int limit) {

        ProductSearchPort port = searchPort.getIfAvailable();
        if (port == null || !port.isAvailable()) {
            return ResponseEntity.ok(ProductSearchPort.Suggestions.empty());
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(port.suggest(q, RequestContext.locale(), Math.clamp(limit, 1, 20)));
    }

    @GetMapping("/popular")
    @Operation(summary = "Trending search terms")
    public ResponseEntity<List<String>> popular(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(15)).cachePublic())
                .body(analytics.popularQueries(Math.clamp(limit, 1, 20)));
    }

    @PostMapping("/click")
    @Operation(summary = "Report which result was clicked",
            description = "Fire-and-forget relevance feedback. Always 204, even for unknown ids — "
                    + "analytics must never surface an error to a shopper.")
    public ResponseEntity<Void> recordClick(@RequestBody ClickRequest request) {
        analytics.recordClick(request.query(), request.productId());
        return ResponseEntity.noContent().build();
    }

    public record SearchResponse(String query,
                                 List<ProductCardResponse> content,
                                 PageResponse.PageMeta page,
                                 ProductSearchPort.Facets facets,
                                 boolean searchIndexUsed) {
    }

    public record ClickRequest(@Size(max = 200) String query, UUID productId) {
    }
}
