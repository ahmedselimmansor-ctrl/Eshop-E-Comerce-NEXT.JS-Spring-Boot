package com.eshop.admin.web;

import com.eshop.admin.service.AdminProductService;
import com.eshop.admin.web.dto.ProductWriteRequest;
import com.eshop.catalog.domain.Product;
import com.eshop.catalog.domain.ProductStatus;
import com.eshop.catalog.service.ProductService;
import com.eshop.catalog.web.dto.ProductCardResponse;
import com.eshop.catalog.web.dto.ProductDetailResponse;
import com.eshop.admin.web.dto.AdminProductDetail;
import com.eshop.admin.web.dto.AdminProductRow;
import com.eshop.common.web.PageResponse;
import com.eshop.common.web.RequestContext;
import com.eshop.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
@Tag(name = "Admin · Products")
public class AdminProductController {

    private final AdminProductService adminProducts;
    private final ProductService products;

    @GetMapping
    @PreAuthorize("hasAuthority('product:read')")
    @Operation(summary = "Search products",
            description = "Includes drafts and archived products, unlike the storefront listing.")
    public PageResponse<AdminProductRow> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy) {

        return PageResponse.of(adminProducts.search(
                q == null || q.isBlank() ? null : q.trim(), status,
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100),
                        Sort.by(Sort.Direction.DESC, safeSort(sortBy))),
                RequestContext.locale()));
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:read')")
    @Operation(summary = "Full product, including drafts")
    public AdminProductDetail detail(@PathVariable UUID productId) {
        return adminProducts.detail(productId);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:write')")
    @Operation(summary = "Create a product",
            description = "Rich text is sanitised server-side. A stock row is created at zero so the "
                    + "product is visible in stock-filtered views rather than silently absent.")
    public ResponseEntity<AdminProductDetail> create(@AuthenticationPrincipal UserPrincipal caller,
                                                     @Valid @RequestBody ProductWriteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminProducts.createAndDescribe(request, caller.id()));
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:write')")
    @Operation(summary = "Replace a product",
            description = "A full replacement - the admin form always submits the whole product.")
    public AdminProductDetail update(@AuthenticationPrincipal UserPrincipal caller,
                                     @PathVariable UUID productId,
                                     @Valid @RequestBody ProductWriteRequest request) {
        return adminProducts.updateAndDescribe(productId, request, caller.id());
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:delete')")
    @Operation(summary = "Archive a product",
            description = "Soft delete: order history keeps resolving, and the slug stays claimed so "
                    + "an old link cannot start pointing at something else.")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal caller,
                                       @PathVariable UUID productId) {
        adminProducts.delete(productId, caller.id());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/bulk-status")
    @PreAuthorize("hasAuthority('product:write')")
    @Operation(summary = "Change status for several products at once")
    public Map<String, Integer> bulkStatus(@AuthenticationPrincipal UserPrincipal caller,
                                           @Valid @RequestBody BulkStatusRequest request) {
        int updated = adminProducts.bulkUpdateStatus(request.productIds(), request.status(), caller.id());
        return Map.of("updated", updated);
    }

    @PostMapping("/{productId}/variants")
    @PreAuthorize("hasAuthority('product:write')")
    @Operation(summary = "Add a variant",
            description = "Switches the product to VARIABLE and creates its stock row. "
                    + "Every attribute value must belong to a variant-axis attribute.")
    public ResponseEntity<AdminProductDetail> addVariant(
            @AuthenticationPrincipal UserPrincipal caller,
            @PathVariable UUID productId,
            @Valid @RequestBody ProductWriteRequest.VariantSpec request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminProducts.addVariantAndDescribe(productId, request, caller.id()));
    }

    @DeleteMapping("/variants/{variantId}")
    @PreAuthorize("hasAuthority('product:write')")
    @Operation(summary = "Deactivate a variant",
            description = "Not deleted - order items reference it and the SKU stays reserved.")
    public ResponseEntity<Void> deleteVariant(@AuthenticationPrincipal UserPrincipal caller,
                                              @PathVariable UUID variantId) {
        adminProducts.deleteVariant(variantId, caller.id());
        return ResponseEntity.noContent().build();
    }

    /** Whitelisted so a sort parameter cannot name an arbitrary column. */
    private static String safeSort(String requested) {
        return List.of("createdAt", "updatedAt", "basePrice", "soldCount", "viewCount", "ratingAvg")
                .contains(requested) ? requested : "createdAt";
    }

    public record BulkStatusRequest(@NotEmpty List<UUID> productIds, @NotNull ProductStatus status) {
    }
}
