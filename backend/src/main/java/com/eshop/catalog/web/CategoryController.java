package com.eshop.catalog.web;

import com.eshop.catalog.domain.Category;
import com.eshop.catalog.service.CategoryService;
import com.eshop.catalog.web.dto.CategoryResponse;
import com.eshop.common.web.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Public taxonomy endpoints.
 *
 * <p>Responses carry {@code s-maxage} so Cloud CDN serves the navigation tree at the edge —
 * it is requested on every page load and changes rarely. {@code stale-while-revalidate} lets the
 * CDN keep serving the previous copy while it refreshes, so a slow origin never blocks a render.
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
@Tag(name = "Catalog")
@SecurityRequirements
public class CategoryController {

    private static final CacheControl PUBLIC_CACHE = CacheControl
            .maxAge(Duration.ofMinutes(1))
            .cachePublic()
            .staleWhileRevalidate(Duration.ofMinutes(10));

    private final CategoryService categories;

    @GetMapping
    @Operation(summary = "List all active categories, flat")
    public ResponseEntity<List<CategoryResponse>> list() {
        String locale = RequestContext.locale();
        List<CategoryResponse> body = categories.flat(true).stream()
                .map(category -> CategoryResponse.flat(category, locale))
                .toList();

        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(body);
    }

    @GetMapping("/tree")
    @Operation(summary = "Category tree",
            description = "Nested, ordered by position. Drives the navigation menu.")
    public ResponseEntity<List<CategoryResponse>> tree() {
        List<CategoryResponse> body = CategoryResponse.tree(categories.tree(true), RequestContext.locale());
        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(body);
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Category by slug",
            description = "Includes ancestors for breadcrumbs and immediate children for sub-navigation.")
    public ResponseEntity<CategoryDetail> bySlug(@PathVariable String slug) {
        String locale = RequestContext.locale();
        Category category = categories.requireBySlug(slug);

        CategoryDetail body = new CategoryDetail(
                CategoryResponse.flat(category, locale),
                categories.ancestorsOf(category).stream()
                        .map(ancestor -> CategoryResponse.flat(ancestor, locale))
                        .toList(),
                categories.childrenOf(category.getId()).stream()
                        .filter(Category::isActive)
                        .map(child -> CategoryResponse.flat(child, locale))
                        .toList());

        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(body);
    }

    public record CategoryDetail(CategoryResponse category,
                                 List<CategoryResponse> breadcrumb,
                                 List<CategoryResponse> children) {
    }
}
